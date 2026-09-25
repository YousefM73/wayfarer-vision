package com.example.glassesview

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.ViewModel
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

class LiveViewModel : ViewModel() {

  companion object {
    private const val TAG = "GlassesView"

    // MEDIUM (504x896) holds up best over Bluetooth. HIGH is 720x1280, LOW is 360x640.
    private val QUALITY = VideoQuality.MEDIUM
    private const val FPS = 24 // valid: 2, 7, 15, 24, 30

    // Extra delay that absorbs Bluetooth bunching. Higher is smoother, lower is more live.
    private const val PLAYOUT_DELAY_MS = 250L
  }

  private val _ui = MutableStateFlow(LiveUiState())
  val ui: StateFlow<LiveUiState> = _ui.asStateFlow()

  private val converter = I420Converter()
  private val _frames = MutableStateFlow<Bitmap?>(null)

  /** The latest frame to draw, published at its playback time. */
  val frames: StateFlow<Bitmap?> = _frames.asStateFlow()

  private var session: DeviceSession? = null
  private var camera: Camera? = null
  private val jobs = mutableListOf<Job>()

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

  fun showMessage(message: String) {
    _ui.update { it.copy(message = message) }
  }

  fun onBluetoothDenied() {
    _ui.update { it.copy(bluetoothDenied = true) }
  }

  fun goLive(requestCameraPermission: suspend () -> PermissionStatus) {
    if (session != null) return
    _ui.update {
      it.copy(streaming = Phase.Connecting, glassesUpdateRequired = false, message = null)
    }
    viewModelScope.launch {
      val current =
          Wearables.checkPermissionStatus(Permission.CAMERA).getOrDefault(PermissionStatus.Denied)
      val granted =
          current == PermissionStatus.Granted ||
              requestCameraPermission() == PermissionStatus.Granted
      if (granted) openSession() else fail("Camera access wasn't allowed in Meta AI.")
    }
  }

  fun stop() {
    teardown()
  }

  private fun openSession() {
    Wearables.createSession(AutoDeviceSelector())
        .fold(
            onSuccess = { created ->
              session = created
              // Subscribe before start() so no transition is missed.
              jobs += viewModelScope.launch {
                created.errors.collect { error ->
                  if (error == DeviceSessionError.DAT_APP_ON_THE_GLASSES_UPDATE_REQUIRED) {
                    // The toolkit app on the glasses is missing or too old; Meta AI installs it.
                    Log.w(TAG, error.description)
                    teardown()
                    _ui.update { it.copy(glassesUpdateRequired = true, message = null) }
                  } else {
                    fail(error.description)
                  }
                }
              }
              jobs += viewModelScope.launch {
                var started = false
                created.state.collect { state ->
                  when (state) {
                    DeviceSessionState.STARTED -> {
                      started = true
                      if (camera == null) attachCamera(created)
                    }
                    DeviceSessionState.STOPPED -> if (started) teardown()
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
        .addCamera(StreamConfiguration(videoQuality = QUALITY, frameRate = FPS))
        .fold(
            onSuccess = { added ->
              camera = added
              val stream = added.stream
              val buffer = Channel<TimedFrame>(64, BufferOverflow.DROP_OLDEST)
              jobs += viewModelScope.launch(Dispatchers.Default) {
                stream.videoStream.collect { frame ->
                  if (frame.isCompressed) return@collect
                  // Copy out of the SDK's buffer, which it may reuse after this returns.
                  val src = frame.buffer.duplicate()
                  val bytes = ByteArray(src.remaining()).also { src.get(it) }
                  buffer.trySend(
                      TimedFrame(bytes, frame.width, frame.height, frame.presentationTimeUs / 1000))
                }
              }
              jobs += viewModelScope.launch(Dispatchers.Default) { playOut(buffer) }
              jobs += viewModelScope.launch {
                var active = false
                stream.state.collect { state ->
                  when (state) {
                    StreamState.STREAMING -> {
                      active = true
                      _ui.update { it.copy(streaming = Phase.Live) }
                    }
                    StreamState.PAUSED -> _ui.update { it.copy(streaming = Phase.Paused) }
                    StreamState.STOPPED,
                    StreamState.CLOSED -> if (active) teardown()
                    else -> Unit
                  }
                }
              }
              jobs += viewModelScope.launch {
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

  private class TimedFrame(val yuv: ByteArray, val width: Int, val height: Int, val ptsMs: Long)

  /**
   * Jitter buffer. Frames leave the glasses at a steady rate but arrive over Bluetooth in bunches,
   * so each one is held until [PLAYOUT_DELAY_MS] after its capture-time slot, then shown. That
   * turns bursts back into even motion. A frame that still arrives late is shown at once and the
   * schedule slides back to match it; if frames start arriving far ahead of schedule (delay has
   * crept up, or timestamps jumped) the schedule is reset to the target delay.
   */
  private suspend fun playOut(buffer: Channel<TimedFrame>) {
    var baseMs = 0L // on-screen time for pts 0
    var anchored = false
    for (timed in buffer) {
      val now = SystemClock.elapsedRealtime()
      val late = now - (baseMs + timed.ptsMs)
      when {
        !anchored || -late > 2 * PLAYOUT_DELAY_MS -> {
          baseMs = now + PLAYOUT_DELAY_MS - timed.ptsMs
          anchored = true
        }
        late > 0 -> baseMs += late
      }
      val dueMs = baseMs + timed.ptsMs
      if (dueMs > now) delay(dueMs - now)
      val bitmap = converter.convert(timed.yuv, timed.width, timed.height) ?: continue
      _frames.value = bitmap
    }
  }

  private fun fail(message: String) {
    Log.e(TAG, message)
    teardown()
    _ui.update { it.copy(message = message) }
  }

  private fun teardown() {
    jobs.forEach { it.cancel() }
    jobs.clear()
    camera?.stop()
    camera = null
    session?.stop()
    session = null
    _frames.value = null
    _ui.update { it.copy(streaming = null) }
  }

  override fun onCleared() {
    teardown()
  }
}
