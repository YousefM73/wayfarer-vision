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
import com.meta.wearable.dat.motion.Motion
import com.meta.wearable.dat.motion.addMotion
import com.meta.wearable.dat.motion.removeMotion
import com.meta.wearable.dat.motion.types.MotionConfiguration
import com.meta.wearable.dat.motion.types.MotionSamplingRate
import com.meta.wearable.dat.motion.types.MotionState
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
    val bufferMs: Long = AUTO_BUFFER, // smoothing delay in ms, or AUTO_BUFFER to size it live
    val toComputer: Boolean = false, // serve the compressed stream over RTSP instead of showing it
    val tracking: Boolean = false, // position and heading from MultiSet; needs its keys
) {
  companion object {
    // Portrait frame sizes the SDK streams at each quality.
    val QUALITIES =
        linkedMapOf(
            VideoQuality.LOW to (360 to 640),
            VideoQuality.MEDIUM to (504 to 896),
            VideoQuality.HIGH to (720 to 1280),
        )
    val FRAME_RATES = listOf(2, 7, 15, 24, 30) // every rate the SDK accepts; lower runs cooler
    const val AUTO_BUFFER = -1L
    val BUFFERS = listOf(AUTO_BUFFER, 0L, 100L, 200L, 300L, 500L)
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
    val tracking: Boolean = false, // whether the open camera tracks head and position
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

    // How far back the jitter buffer looks: for its fastest-delivery baseline and, in auto mode,
    // for how late frames have been arriving.
    private const val WINDOW_MS = 10_000L

    // Auto buffer: cover all but the latest few percent of recent frames, within sane bounds.
    private const val AUTO_PERCENTILE = 97
    private const val AUTO_MARGIN_MS = 20L
    private const val AUTO_MIN_MS = 60L
    private const val AUTO_MAX_MS = 800L
    private const val AUTO_START_MS = 200L // until there is some history to go on
    private const val AUTO_MIN_SAMPLES = 15
    private const val AUTO_RETUNE_FRAMES = 12

    // Port the RTSP server listens on in computer mode (554 is reserved for system apps).
    const val PORT = 8554

    // After a position query fails, wait at least this long: at the fastest pace a wrong key or
    // a dead network would otherwise mean a failing request every second.
    private const val LOCATE_RETRY_MS = 5_000L

    // Motion sensors: how long a start may take to produce its first sample, how long running
    // sensors may go quiet, and how many restarts in a row to try before giving up.
    private const val MOTION_START_MS = 5_000L
    private const val MOTION_QUIET_MS = 3_000L
    private const val MOTION_RESTARTS = 3
    private const val MOTION_RESTART_PAUSE_MS = 750L
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
              bufferMs = prefs.getLong("bufferMs", StreamSettings.AUTO_BUFFER),
              toComputer = prefs.getBoolean("toComputer", false),
              tracking = prefs.getBoolean("tracking", false),
          ))
  val settings: StateFlow<StreamSettings> = _settings.asStateFlow()

  // Settings the open camera is using; changes on the camera screen apply next time it opens.
  private var opened = StreamSettings()

  private val converter = I420Converter()
  private val _frames = MutableStateFlow<Bitmap?>(null)

  /** The latest frame to draw, published at its playback time. */
  val frames: StateFlow<Bitmap?> = _frames.asStateFlow()

  private val _bufferNow = MutableStateFlow(0L)

  /** The smoothing delay in use right now, in ms; it moves while the buffer is on auto. */
  val bufferNow: StateFlow<Long> = _bufferNow.asStateFlow()

  // Where the auto buffer left off, so the next open starts from a delay that suited this link.
  @Volatile private var autoDelayMs = AUTO_START_MS

  // The session with the glasses stays open between camera views and only the camera is added and
  // removed. Ending a session makes the SDK report the glasses as disconnected for a while, which
  // would fail the next open.
  private var session: DeviceSession? = null
  private var sessionStarted = false
  private val sessionJobs = mutableListOf<Job>()
  private var camera: Camera? = null
  private var wantCamera = false
  private val cameraJobs = mutableListOf<Job>()
  private var server: RtspServer? = null
  private val serverJobs = mutableListOf<Job>()

  private val _tracking = MutableStateFlow(TrackingState())

  /** Head angles and map position while tracking is on. */
  val tracking: StateFlow<TrackingState> = _tracking.asStateFlow()

  private var motion: Motion? = null
  private val trackingJobs = mutableListOf<Job>()
  @Volatile private var motionSamples = 0L // received so far; shows whether the sensors are alive
  @Volatile private var latestFrame: TimedFrame? = null // newest decoded frame, for location

  private val _multiSet =
      MutableStateFlow(
          MultiSetConfig(
              clientId = prefs.getString("multisetClientId", null).orEmpty(),
              clientSecret = prefs.getString("multisetClientSecret", null).orEmpty(),
              mapCode = prefs.getString("multisetMapCode", null).orEmpty(),
              everySeconds =
                  prefs.getInt("multisetEverySeconds", 0).takeIf { it in MultiSetConfig.INTERVALS }
                      ?: MultiSetConfig().everySeconds,
          ))

  /** The MultiSet account and map typed into settings. Kept in this app's private storage. */
  val multiSet: StateFlow<MultiSetConfig> = _multiSet.asStateFlow()

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
        .putBoolean("tracking", next.tracking)
        .apply()
  }

  fun updateMultiSet(change: (MultiSetConfig) -> MultiSetConfig) {
    val next = change(_multiSet.value)
    _multiSet.value = next
    prefs
        .edit()
        .putString("multisetClientId", next.clientId)
        .putString("multisetClientSecret", next.clientSecret)
        .putString("multisetMapCode", next.mapCode)
        .putInt("multisetEverySeconds", next.everySeconds)
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
    // Tracking can't be chosen without MultiSet keys; a choice saved before they were removed
    // doesn't count either.
    opened = _settings.value.let { it.copy(tracking = it.tracking && _multiSet.value.complete) }
    if (opened.toComputer) {
      // Started now, while the app is on screen: Android won't start a foreground service from
      // the background, and the app may be off screen by the time the session is up.
      StreamService.start(getApplication(), "Starting camera…", "")
    }
    _ui.update {
      it.copy(
          streaming = Phase.Connecting,
          toComputer = opened.toComputer,
          tracking = opened.tracking,
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

  /**
   * The app left the screen. In phone mode there is nothing to show then, so the camera closes.
   * In computer mode streaming carries on under [StreamService], with the screen off or another
   * app in front, until Stop is tapped here or on its notification.
   */
  fun onBackground() {
    if (_ui.value.streaming != null && !opened.toComputer) stop()
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
    val started = RtspServer(PORT, viewModelScope).also { server = it }
    started.start()
    val address = RtspServer.localAddress()?.let { ip -> "$ip:$PORT" }
    _ui.update { it.copy(streaming = Phase.Listening, serverAddress = address) }
    // The notification says where to connect, and its Stop ends the stream from anywhere.
    val app = getApplication<Application>()
    val waiting =
        "Waiting for a computer" to (address?.let { "rtsp://$it/live" } ?: "Not on a network")
    StreamService.update(app, waiting.first, waiting.second)
    serverJobs += viewModelScope.launch { StreamService.stopRequests.collect { stop() } }
    // The glasses stream only while a player is playing: each one gets a fresh stream
    // (parameter sets and a keyframe first), and the glasses stay cool while nobody is watching.
    serverJobs +=
        viewModelScope.launch {
          started.client.collect { client ->
            _ui.update { it.copy(clientAddress = client) }
            if (client != null) {
              StreamService.update(app, "Streaming to computer", client)
              if (camera == null) attachCamera(session)
            } else {
              StreamService.update(app, waiting.first, waiting.second)
              if (camera != null) cameraEnded()
            }
          }
        }
  }

  private fun stopServer() {
    serverJobs.forEach { it.cancel() }
    serverJobs.clear()
    server?.stop()
    server = null
    StreamService.stop(getApplication())
  }

  private fun openSession() {
    Wearables.createSession(AutoDeviceSelector())
        .fold(
            onSuccess = { created ->
              session = created
              // Subscribe before start() so no transition is missed.
              sessionJobs += viewModelScope.launch {
                created.errors.collect { error ->
                  // The toolkit app on the glasses is missing or too old; Meta AI installs it.
                  // The SDK often reports that refusal as a plain "ended by device" before the
                  // session ever starts, so treat that the same way.
                  val refused =
                      error == DeviceSessionError.SESSION_ENDED_BY_DEVICE && !sessionStarted
                  if (error == DeviceSessionError.DAT_APP_ON_THE_GLASSES_UPDATE_REQUIRED || refused) {
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
              // Decoded frames are big and independent, so cap the queue and shed the oldest.
              // Compressed frames are small and each depends on the last, so never shed any.
              val buffer =
                  if (opened.toComputer) Channel<TimedFrame>(Channel.UNLIMITED)
                  else Channel<TimedFrame>(64, BufferOverflow.DROP_OLDEST)
              // A compressed frame arrives wrapped around the transport's own buffer, which is
              // reused for the next frame as soon as the SDK's callback returns. So the copy has
              // to happen inside that callback: collecting unconfined runs this block within the
              // SDK's emit, on its thread. On any other dispatcher the copy runs late and picks
              // up the following frame's bytes; that loses a reference frame and smears the
              // picture until the next keyframe.
              cameraJobs += viewModelScope.launch(Dispatchers.Unconfined) {
                stream.videoStream.collect { frame ->
                  if (frame.isCompressed != opened.toComputer) return@collect
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
                    { frame -> server?.send(frame.data, frame.ptsMs) }
                  } else {
                    // The SDK delivers decoded frames from a thread pool, so two can swap
                    // places. Showing the older one second would step back in time; skip it.
                    var lastPtsMs = Long.MIN_VALUE
                    val draw: (TimedFrame) -> Unit = { frame ->
                      if (frame.ptsMs >= lastPtsMs) {
                        lastPtsMs = frame.ptsMs
                        if (opened.tracking) latestFrame = frame
                        converter.convert(frame.data, frame.width, frame.height)?.let {
                          _frames.value = it
                        }
                      }
                    }
                    draw
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
                      // Not before: motion sensors asked for ahead of the first frames never
                      // deliver a sample.
                      if (opened.tracking) startTracking(session)
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

  /**
   * Position and heading from MultiSet, plus head angles from the glasses' own motion sensors
   * should they send any. Current glasses accept that request and then stay silent.
   */
  private fun startTracking(session: DeviceSession) {
    if (motion != null || trackingJobs.isNotEmpty()) return
    _tracking.value = TrackingState()
    session
        .addMotion(MotionConfiguration(samplingRate = MotionSamplingRate.HZ_10))
        .fold(
            onSuccess = { added ->
              motion = added
              // Collect before start() so the first samples aren't missed.
              trackingJobs +=
                  viewModelScope.launch(Dispatchers.Default) {
                    added.samples.collect { sample ->
                      motionSamples++
                      val head = headAngles(sample) ?: return@collect
                      _tracking.update { it.copy(head = head, headNote = null) }
                    }
                  }
              trackingJobs +=
                  viewModelScope.launch {
                    added.errors.collect { error ->
                      if (error != null) {
                        Log.w(TAG, "Motion error: ${error.description}")
                        _tracking.update { it.copy(headNote = "Head: ${error.description}") }
                      }
                    }
                  }
              trackingJobs += viewModelScope.launch { keepMotionAlive(added) }
              added.start()
            },
            onFailure = { error, _ ->
              Log.w(TAG, "Motion unavailable: ${error.description}")
              _tracking.update { it.copy(headNote = "Head tracking unavailable") }
            },
        )
    trackingJobs += viewModelScope.launch(Dispatchers.IO) { locate() }
  }

  /**
   * Restarts the motion sensors when they go quiet. The glasses drop them without a word when
   * the camera restarts, which it also does to switch resolution, and a start that never yields
   * a sample stays "starting" for good: the SDK has no timeout for it.
   */
  private suspend fun keepMotionAlive(motion: Motion) {
    var seen = motionSamples
    var quietSince = SystemClock.elapsedRealtime()
    var restarts = 0 // in a row, with no sample in between
    while (true) {
      delay(1_000)
      val now = SystemClock.elapsedRealtime()
      val state = motion.state.value
      // Paused sensors resume by themselves, with the session or the stream that paused them.
      val paused = state == MotionState.PAUSED || _ui.value.streaming == Phase.Paused
      if (motionSamples != seen || paused) {
        if (motionSamples != seen) restarts = 0
        seen = motionSamples
        quietSince = now
        continue
      }
      val patience = if (state == MotionState.STARTING) MOTION_START_MS else MOTION_QUIET_MS
      if (now - quietSince < patience) continue
      if (restarts == MOTION_RESTARTS) {
        Log.w(TAG, "Motion: still no samples after $restarts restarts")
        _tracking.update { it.copy(head = null, headNote = "Head: no data from the glasses") }
        return
      }
      restarts++
      Log.w(TAG, "Motion: no samples for ${now - quietSince} ms while $state; restart $restarts")
      _tracking.update { it.copy(head = null) }
      motion.stop() // synchronous; a start() is ignored unless the sensors are stopped
      delay(MOTION_RESTART_PAUSE_MS)
      motion.start()
      quietSince = SystemClock.elapsedRealtime()
    }
  }

  /** Asks MultiSet where the newest frame was taken, every few seconds while frames arrive. */
  private suspend fun locate() {
    if (opened.toComputer) {
      // Nothing is decoded on the phone in computer mode, so there is no picture to send.
      _tracking.update { it.copy(locationNote = "Location needs Show on: This phone") }
      return
    }
    val config = _multiSet.value
    val client = MultiSetClient(config)
    _tracking.update { it.copy(locationNote = "Locating…") }
    var sent: TimedFrame? = null
    while (true) {
      val frame = latestFrame
      // Nothing new to ask about: no frame yet, or the stream is paused on the last one.
      if (frame == null || frame === sent) {
        delay(200)
        continue
      }
      sent = frame
      val startedMs = SystemClock.elapsedRealtime()
      var everyMs = config.everySeconds * 1_000L
      val jpeg = i420ToJpeg(frame.data, frame.width, frame.height)
      when (val fix = client.locate(jpeg, frame.width, frame.height)) {
        is MultiSetClient.Fix.Found ->
            _tracking.update { it.copy(pose = fix.pose, locationNote = null) }
        is MultiSetClient.Fix.NotFound ->
            _tracking.update { it.copy(locationNote = "no match in the map") }
        is MultiSetClient.Fix.Failed -> {
          Log.w(TAG, "MultiSet query failed: ${fix.reason}")
          _tracking.update { it.copy(locationNote = "Location: ${fix.reason}") }
          everyMs = maxOf(everyMs, LOCATE_RETRY_MS)
        }
      }
      // Paced from one query's start to the next, so a slow reply eats into the wait rather
      // than adding to it.
      delay((startedMs + everyMs - SystemClock.elapsedRealtime()).coerceAtLeast(0))
    }
  }

  private fun stopTracking() {
    trackingJobs.forEach { it.cancel() }
    trackingJobs.clear()
    motion?.let {
      it.stop()
      session?.removeMotion()?.onFailure { error, _ ->
        Log.w(TAG, "Couldn't remove motion: ${error.description}")
      }
    }
    motion = null
    latestFrame = null
    _tracking.value = TrackingState()
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
   * Each frame's transit time is `arrived - pts` (its transport delay plus a constant clock
   * offset); the fastest delivery over the last [WINDOW_MS] is the baseline. A frame is shown a
   * fixed delay after the time it would have arrived at that baseline, so bunched frames play
   * back at their capture pace, and frames later than that are shown at once. Because the
   * baseline is a recent minimum, a stall never pushes the delay up for good.
   *
   * [setting] is that delay in ms, or [StreamSettings.AUTO_BUFFER] to size it from the link: the
   * delay then follows how late recent frames actually were (their [AUTO_PERCENTILE]th
   * percentile), growing at once when stalls get longer and shrinking a millisecond per frame,
   * too slowly to see, when the link clears up.
   */
  private suspend fun playOut(
      buffer: Channel<TimedFrame>,
      setting: Long,
      show: (TimedFrame) -> Unit,
  ) {
    val auto = setting == StreamSettings.AUTO_BUFFER
    var delayMs = if (auto) autoDelayMs else setting
    var autoTarget = delayMs
    var sinceRetune = 0
    _bufferNow.value = delayMs
    // (arrival time, transit) of the frames from the last WINDOW_MS. Only these two numbers are
    // kept, not the frames, which would pin seconds of video in memory.
    val recent = ArrayDeque<LongArray>()
    for (timed in buffer) {
      val transit = timed.arrivedMs - timed.ptsMs
      recent.addLast(longArrayOf(timed.arrivedMs, transit))
      while (recent.first()[0] < timed.arrivedMs - WINDOW_MS) recent.removeFirst()
      val baseline = recent.minOf { it[1] }
      if (auto) {
        if (++sinceRetune >= AUTO_RETUNE_FRAMES && recent.size >= AUTO_MIN_SAMPLES) {
          sinceRetune = 0
          val lateness = LongArray(recent.size) { recent[it][1] - baseline }.also { it.sort() }
          autoTarget =
              (lateness[(lateness.size - 1) * AUTO_PERCENTILE / 100] + AUTO_MARGIN_MS)
                  .coerceIn(AUTO_MIN_MS, AUTO_MAX_MS)
        }
        delayMs = if (autoTarget > delayMs) autoTarget else maxOf(autoTarget, delayMs - 1)
        autoDelayMs = delayMs
        _bufferNow.value = delayMs / 10 * 10 // steps of 10 ms are plenty for a readout
      }
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
    stopTracking() // it runs alongside the stream
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
