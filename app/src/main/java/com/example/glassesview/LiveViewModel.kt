package com.example.glassesview

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.meta.wearable.dat.camera.Camera
import com.meta.wearable.dat.camera.addCamera
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.StreamError
import com.meta.wearable.dat.camera.types.StreamState
import com.meta.wearable.dat.camera.types.VideoQuality
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.AutoDeviceSelector
import com.meta.wearable.dat.core.session.DeviceSession
import com.meta.wearable.dat.core.session.DeviceSessionState
import com.meta.wearable.dat.core.types.DeviceSessionError
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.meta.wearable.dat.core.types.RegistrationState
import com.meta.wearable.dat.core.types.WearablesError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class Phase {
  NeedsBluetooth,
  NeedsRegistration,
  Registering,
  GlassesUpdateRequired,
  WaitingForGlasses,
  Ready,
  Connecting,
  Listening, // serving to a computer; the glasses stream once a player connects
  Live,
  Paused,
}

/** What the user picks on the camera screen. Applied when the camera is opened. */
data class StreamSettings(
    val quality: VideoQuality = VideoQuality.MEDIUM,
    val fps: Int = 24,
    val bufferMs: Long = 200,
    val toComputer: Boolean = false, // serve the compressed stream over TCP instead of showing it
) {
  companion object {
    // Portrait frame sizes the SDK streams at each quality.
    val QUALITIES =
        linkedMapOf(
            VideoQuality.LOW to (360 to 640),
            VideoQuality.MEDIUM to (504 to 896),
            VideoQuality.HIGH to (720 to 1280),
        )
    val FRAME_RATES = listOf(15, 24, 30) // the SDK also accepts 2 and 7
    val BUFFERS = listOf(100L, 200L, 300L)
  }
}

data class LiveUiState(
    val sdkReady: Boolean = false,
    val bluetoothDenied: Boolean = false,
    val registration: RegistrationState? = null,
    val hasGlasses: Boolean = false,
    val glassesUpdateRequired: Boolean = false,
    val streaming: Phase? = null, // Connecting, Listening, Live or Paused while a session is open
    val toComputer: Boolean = false, // what the open camera is doing
    val serverAddress: String? = null, // host:port on the local network, in computer mode
    val clientAddress: String? = null, // the connected player, in computer mode
    val message: String? = null,
) {
  val phase: Phase
    get() =
        when {
          streaming != null -> streaming
          glassesUpdateRequired -> Phase.GlassesUpdateRequired
          !sdkReady -> Phase.NeedsBluetooth
          registration == RegistrationState.REGISTERING -> Phase.Registering
          registration != RegistrationState.REGISTERED -> Phase.NeedsRegistration
          !hasGlasses -> Phase.WaitingForGlasses
          else -> Phase.Ready
        }
}

class LiveViewModel(application: Application) : AndroidViewModel(application) {

  companion object {
    private const val TAG = "GlassesView"

    // How far back the jitter buffer looks for its fastest-delivery baseline.
    private const val BASELINE_WINDOW_MS = 3_000L

    // TCP port the compressed stream is served on in computer mode.
    const val PORT = 5000
  }

  private val _ui = MutableStateFlow(LiveUiState())
  val ui: StateFlow<LiveUiState> = _ui.asStateFlow()

  private val prefs = application.getSharedPreferences("stream_settings", Context.MODE_PRIVATE)
  private val _settings =
      MutableStateFlow(
          StreamSettings(
              quality =
                  VideoQuality.entries.firstOrNull { it.name == prefs.getString("quality", null) }
                      ?: VideoQuality.MEDIUM,
              fps = prefs.getInt("fps", 24),
              bufferMs = prefs.getLong("bufferMs", 200),
              toComputer = prefs.getBoolean("toComputer", false),
          ))
  val settings: StateFlow<StreamSettings> = _settings.asStateFlow()

  // Settings the open camera is using; changes on the camera screen apply next time it opens.
  private var opened = StreamSettings()

  private val converter = I420Converter()
  private val _frames = MutableStateFlow<Bitmap?>(null)

  /** The latest frame to draw, published at its playback time. */
  val frames: StateFlow<Bitmap?> = _frames.asStateFlow()

  // The session with the glasses stays open between camera views and only the camera is added and
  // removed. Ending a session makes the SDK report the glasses as disconnected for a while, which
  // would fail the next open.
  private var session: DeviceSession? = null
  private var sessionStarted = false
  private val sessionJobs = mutableListOf<Job>()
  private var camera: Camera? = null
  private var wantCamera = false
  private val cameraJobs = mutableListOf<Job>()
  private var server: StreamServer? = null
  private var serverJob: Job? = null

  fun initialize(context: Context) {
    if (_ui.value.sdkReady) return
    // The SDK is set up once per process. A fresh ViewModel (a new activity instance, e.g. after
    // coming back from Meta AI) finds it already initialized, which is fine.
    var ready = false
    Wearables.initialize(context)
        .onSuccess { ready = true }
        .onFailure { error, _ ->
          if (error == WearablesError.ALREADY_INITIALIZED) {
            ready = true
          } else {
            _ui.update { it.copy(message = error.description) }
          }
        }
    if (!ready) return
    _ui.update { it.copy(sdkReady = true, bluetoothDenied = false) }
    viewModelScope.launch {
      Wearables.registrationState.collect { state -> _ui.update { it.copy(registration = state) } }
    }
    viewModelScope.launch {
      Wearables.devices.collect { devices ->
        _ui.update { it.copy(hasGlasses = devices.isNotEmpty()) }
      }
    }
  }

  fun updateSettings(change: (StreamSettings) -> StreamSettings) {
    val next = change(_settings.value)
    _settings.value = next
    prefs
        .edit()
        .putString("quality", next.quality.name)
        .putInt("fps", next.fps)
        .putLong("bufferMs", next.bufferMs)
        .putBoolean("toComputer", next.toComputer)
        .apply()
  }

  fun showMessage(message: String) {
    _ui.update { it.copy(message = message) }
  }

  fun onBluetoothDenied() {
    _ui.update { it.copy(bluetoothDenied = true) }
  }

  fun goLive(requestCameraPermission: suspend () -> PermissionStatus) {
    if (_ui.value.streaming != null) return
    wantCamera = true
    opened = _settings.value
    _ui.update {
      it.copy(
          streaming = Phase.Connecting,
          toComputer = opened.toComputer,
          glassesUpdateRequired = false,
          message = null,
      )
    }
    viewModelScope.launch {
      var status: PermissionStatus? = null
      var problem: String? = null
      Wearables.checkPermissionStatus(Permission.CAMERA)
          .onSuccess { status = it }
          .onFailure { error, _ -> problem = error.description }
      val reason = problem
      when {
        // The check fails when the glasses are asleep or disconnected. That isn't a denial, so
        // don't send the user to Meta AI for a permission they may already have granted.
        reason != null -> fail("Couldn't reach your glasses: $reason")
        status == PermissionStatus.Granted -> startCamera()
        requestCameraPermission() == PermissionStatus.Granted -> startCamera()
        else -> fail("Camera access wasn't allowed in Meta AI.")
      }
    }
  }

  /** Closes the camera (and server) but keeps the session with the glasses for a quick reopen. */
  fun stop() {
    wantCamera = false
    closeCamera()
    stopServer()
    _ui.update { it.copy(streaming = null, serverAddress = null, clientAddress = null) }
  }

  /** The app left the screen: don't keep streaming in the background. */
  fun onBackground() {
    if (_ui.value.streaming != null) stop()
  }

  private fun startCamera() {
    if (!wantCamera) return // closed while waiting for permission
    val current = session
    when {
      current == null -> openSession()
      sessionStarted && camera == null -> onSessionReady(current)
      else -> Unit // still starting; the session's state collector continues from STARTED
    }
  }

  /** The session is up: stream to this phone now, or wait for a computer to connect. */
  private fun onSessionReady(session: DeviceSession) {
    if (opened.toComputer) startServer(session) else attachCamera(session)
  }

  private fun startServer(session: DeviceSession) {
    if (server != null) return
    val started = StreamServer(PORT, viewModelScope).also { server = it }
    started.start()
    _ui.update {
      it.copy(
          streaming = Phase.Listening,
          serverAddress = StreamServer.localAddress()?.let { ip -> "$ip:$PORT" },
      )
    }
    // The glasses stream only while a player is connected: each connection gets a fresh stream
    // (parameter sets and a keyframe first), and the glasses stay cool while nobody is watching.
    serverJob =
        viewModelScope.launch {
          started.client.collect { client ->
            _ui.update { it.copy(clientAddress = client) }
            if (client != null) {
              if (camera == null) attachCamera(session)
            } else if (camera != null) {
              cameraEnded()
            }
          }
        }
  }

  private fun stopServer() {
    serverJob?.cancel()
    serverJob = null
    server?.stop()
    server = null
  }

  private fun openSession() {
    Wearables.createSession(AutoDeviceSelector())
        .fold(
            onSuccess = { created ->
              session = created
              // Subscribe before start() so no transition is missed.
              sessionJobs += viewModelScope.launch {
                created.errors.collect { error ->
                  if (error == DeviceSessionError.DAT_APP_ON_THE_GLASSES_UPDATE_REQUIRED) {
                    // The toolkit app on the glasses is missing or too old; Meta AI installs it.
                    Log.w(TAG, error.description)
                    endSession()
                    _ui.update { it.copy(glassesUpdateRequired = true, message = null) }
                  } else {
                    fail(explain(error))
                  }
                }
              }
              sessionJobs += viewModelScope.launch {
                created.state.collect { state ->
                  when (state) {
                    DeviceSessionState.STARTED -> {
                      sessionStarted = true
                      if (wantCamera && camera == null) onSessionReady(created)
                    }
                    DeviceSessionState.STOPPED -> if (sessionStarted) endSession()
                    else -> Unit
                  }
                }
              }
              created.start()
            },
            onFailure = { error, _ -> fail(error.description) },
        )
  }

  private fun attachCamera(session: DeviceSession) {
    session
        .addCamera(
            StreamConfiguration(
                videoQuality = opened.quality,
                frameRate = opened.fps,
                // Decoded on the phone to show here; compressed HEVC to pass on to a computer.
                compressVideo = opened.toComputer,
            ))
        .fold(
            onSuccess = { added ->
              camera = added
              val stream = added.stream
              val buffer = Channel<TimedFrame>(64, BufferOverflow.DROP_OLDEST)
              cameraJobs += viewModelScope.launch(Dispatchers.Default) {
                stream.videoStream.collect { frame ->
                  if (frame.isCompressed != opened.toComputer) return@collect
                  // Copy out of the SDK's buffer, which it may reuse after this returns.
                  val src = frame.buffer.duplicate()
                  val bytes = ByteArray(src.remaining()).also { src.get(it) }
                  buffer.trySend(
                      TimedFrame(
                          bytes,
                          frame.width,
                          frame.height,
                          frame.presentationTimeUs / 1000,
                          SystemClock.elapsedRealtime(),
                      ))
                }
              }
              val bufferMs = opened.bufferMs
              val show: (TimedFrame) -> Unit =
                  if (opened.toComputer) {
                    { frame -> server?.send(frame.data) }
                  } else {
                    { frame ->
                      converter.convert(frame.data, frame.width, frame.height)?.let {
                        _frames.value = it
                      }
                    }
                  }
              cameraJobs +=
                  viewModelScope.launch(Dispatchers.Default) { playOut(buffer, bufferMs, show) }
              cameraJobs += viewModelScope.launch {
                // The state replays STOPPED on subscribe; only a STOPPED/CLOSED after the
                // stream has been starting or running means it ended.
                var active = false
                stream.state.collect { state ->
                  when (state) {
                    StreamState.STREAMING -> {
                      active = true
                      _ui.update { it.copy(streaming = Phase.Live) }
                    }
                    StreamState.PAUSED -> _ui.update { it.copy(streaming = Phase.Paused) }
                    StreamState.STOPPED,
                    StreamState.CLOSED -> if (active) cameraEnded()
                    else -> active = true // STARTING, STARTED, STOPPING
                  }
                }
              }
              cameraJobs += viewModelScope.launch {
                stream.errorStream.collect { error ->
                  Log.w(TAG, "Stream error: ${error.description}")
                  if (error == StreamError.CRITICAL_STREAM_ERROR) {
                    // Per the SDK docs the stream is done; usually the glasses never started it.
                    _ui.update {
                      it.copy(
                          message =
                              "The glasses didn't start streaming. Check they're on, nearby and " +
                                  "not connected to another phone.")
                    }
                    cameraEnded()
                  } else {
                    _ui.update { it.copy(message = error.description) }
                  }
                }
              }
              stream.start().onFailure { error, _ -> fail(error.description) }
            },
            onFailure = { error, _ -> fail(error.description) },
        )
  }

  private class TimedFrame(
      val data: ByteArray, // I420 pixels, or one HEVC access unit in computer mode
      val width: Int,
      val height: Int,
      val ptsMs: Long, // capture time on the glasses' clock
      val arrivedMs: Long, // arrival time on the phone's clock
  )

  /**
   * Jitter buffer. Frames leave the glasses at a steady rate but arrive over Bluetooth in bunches.
   * Each frame's transport delay is `arrived - pts` (plus an unknown clock offset); the fastest
   * delivery over the last few seconds is the baseline. A frame is shown [delayMs] after the time
   * it would have arrived at that baseline, so bunched frames play back at their capture pace, and
   * frames later than that are shown at once. Because the baseline is a recent minimum, a stall
   * never pushes the delay up for good: once delivery recovers, delay drops back to [delayMs].
   */
  private suspend fun playOut(
      buffer: Channel<TimedFrame>,
      delayMs: Long,
      show: (TimedFrame) -> Unit,
  ) {
    val recent = ArrayDeque<TimedFrame>() // frames from the last BASELINE_WINDOW_MS, by arrival
    for (timed in buffer) {
      recent.addLast(timed)
      while (recent.first().arrivedMs < timed.arrivedMs - BASELINE_WINDOW_MS) recent.removeFirst()
      val baseline = recent.minOf { it.arrivedMs - it.ptsMs }
      val dueMs = timed.ptsMs + baseline + delayMs
      val now = SystemClock.elapsedRealtime()
      if (dueMs > now) delay(dueMs - now)
      show(timed)
    }
  }

  /** Plain-language versions of the session errors a user can do something about. */
  private fun explain(error: DeviceSessionError): String =
      when (error) {
        DeviceSessionError.THERMAL_CRITICAL,
        DeviceSessionError.THERMAL_EMERGENCY ->
            "Your glasses are too warm to stream. Give them a few minutes to cool down; a lower " +
                "quality and frame rate run cooler."
        DeviceSessionError.BATTERY_CRITICAL,
        DeviceSessionError.PEAK_POWER_SHUTDOWN -> "Your glasses' battery is too low to stream."
        DeviceSessionError.DEVICE_DISCONNECTED ->
            "Lost the connection to your glasses. Check they're on and nearby."
        else -> error.description
      }

  private fun fail(message: String) {
    Log.e(TAG, message)
    endSession()
    _ui.update { it.copy(message = message) }
  }

  private fun closeCamera() {
    cameraJobs.forEach { it.cancel() }
    cameraJobs.clear()
    // Stopping the camera also detaches it, so the next open can add one with new settings.
    camera?.stop()
    camera = null
    _frames.value = null
  }

  /** The stream ended on its own: back to waiting for a player in computer mode, else idle. */
  private fun cameraEnded() {
    closeCamera()
    _ui.update { it.copy(streaming = if (server != null) Phase.Listening else null) }
  }

  private fun endSession() {
    wantCamera = false
    closeCamera()
    stopServer()
    sessionJobs.forEach { it.cancel() }
    sessionJobs.clear()
    session?.stop()
    session = null
    sessionStarted = false
    _ui.update { it.copy(streaming = null, serverAddress = null, clientAddress = null) }
  }

  override fun onCleared() {
    endSession()
  }
}
