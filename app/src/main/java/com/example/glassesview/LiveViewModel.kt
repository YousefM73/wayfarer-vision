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
  Live,
  Paused,
}

/** What the user picks on the camera screen. Applied when the camera is opened. */
data class StreamSettings(
    val quality: VideoQuality = VideoQuality.MEDIUM,
    val fps: Int = 24,
    val bufferMs: Long = 200,
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
    val streaming: Phase? = null, // Connecting, Live or Paused while a session is open
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

  fun initialize(context: Context) {
    if (_ui.value.sdkReady) return
    Wearables.initialize(context)
        .onSuccess {
          _ui.update { it.copy(sdkReady = true, bluetoothDenied = false) }
          viewModelScope.launch {
            Wearables.registrationState.collect { state ->
              _ui.update { it.copy(registration = state) }
            }
          }
          viewModelScope.launch {
            Wearables.devices.collect { devices ->
              _ui.update { it.copy(hasGlasses = devices.isNotEmpty()) }
            }
          }
        }
        .onFailure { error, _ -> _ui.update { it.copy(message = error.description) } }
  }

  fun updateSettings(change: (StreamSettings) -> StreamSettings) {
    val next = change(_settings.value)
    _settings.value = next
    prefs
        .edit()
        .putString("quality", next.quality.name)
        .putInt("fps", next.fps)
        .putLong("bufferMs", next.bufferMs)
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
    _ui.update {
      it.copy(streaming = Phase.Connecting, glassesUpdateRequired = false, message = null)
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

  /** Closes the camera but keeps the session with the glasses, so it can reopen straight away. */
  fun stop() {
    closeCamera()
  }

  /** The app left the screen: don't keep streaming in the background. */
  fun onBackground() {
    if (camera != null) closeCamera()
  }

  private fun startCamera() {
    if (!wantCamera) return // closed while waiting for permission
    val current = session
    when {
      current == null -> openSession()
      sessionStarted && camera == null -> attachCamera(current)
      else -> Unit // still starting; the session's state collector attaches the camera
    }
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
                    fail(error.description)
                  }
                }
              }
              sessionJobs += viewModelScope.launch {
                created.state.collect { state ->
                  when (state) {
                    DeviceSessionState.STARTED -> {
                      sessionStarted = true
                      if (wantCamera && camera == null) attachCamera(created)
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
    opened = _settings.value
    session
        .addCamera(StreamConfiguration(videoQuality = opened.quality, frameRate = opened.fps))
        .fold(
            onSuccess = { added ->
              camera = added
              val stream = added.stream
              val buffer = Channel<TimedFrame>(64, BufferOverflow.DROP_OLDEST)
              cameraJobs += viewModelScope.launch(Dispatchers.Default) {
                stream.videoStream.collect { frame ->
                  if (frame.isCompressed) return@collect
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
              cameraJobs += viewModelScope.launch(Dispatchers.Default) { playOut(buffer, bufferMs) }
              cameraJobs += viewModelScope.launch {
                var active = false
                stream.state.collect { state ->
                  when (state) {
                    StreamState.STREAMING -> {
                      active = true
                      _ui.update { it.copy(streaming = Phase.Live) }
                    }
                    StreamState.PAUSED -> _ui.update { it.copy(streaming = Phase.Paused) }
                    StreamState.STOPPED,
                    StreamState.CLOSED -> if (active) closeCamera()
                    else -> Unit
                  }
                }
              }
              cameraJobs += viewModelScope.launch {
                stream.errorStream.collect { error ->
                  Log.w(TAG, "Stream error: ${error.description}")
                  _ui.update { it.copy(message = error.description) }
                }
              }
              stream.start().onFailure { error, _ -> fail(error.description) }
            },
            onFailure = { error, _ -> fail(error.description) },
        )
  }

  private class TimedFrame(
      val yuv: ByteArray,
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
  private suspend fun playOut(buffer: Channel<TimedFrame>, delayMs: Long) {
    val recent = ArrayDeque<TimedFrame>() // frames from the last BASELINE_WINDOW_MS, by arrival
    for (timed in buffer) {
      recent.addLast(timed)
      while (recent.first().arrivedMs < timed.arrivedMs - BASELINE_WINDOW_MS) recent.removeFirst()
      val baseline = recent.minOf { it.arrivedMs - it.ptsMs }
      val dueMs = timed.ptsMs + baseline + delayMs
      val now = SystemClock.elapsedRealtime()
      if (dueMs > now) delay(dueMs - now)
      val bitmap = converter.convert(timed.yuv, timed.width, timed.height) ?: continue
      _frames.value = bitmap
    }
  }

  private fun fail(message: String) {
    Log.e(TAG, message)
    endSession()
    _ui.update { it.copy(message = message) }
  }

  private fun closeCamera() {
    wantCamera = false
    cameraJobs.forEach { it.cancel() }
    cameraJobs.clear()
    // Stopping the camera also detaches it, so the next open can add one with new settings.
    camera?.stop()
    camera = null
    _frames.value = null
    _ui.update { it.copy(streaming = null) }
  }

  private fun endSession() {
    closeCamera()
    sessionJobs.forEach { it.cancel() }
    sessionJobs.clear()
    session?.stop()
    session = null
    sessionStarted = false
  }

  override fun onCleared() {
    endSession()
  }
}
