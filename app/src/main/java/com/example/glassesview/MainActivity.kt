package com.example.glassesview

import android.Manifest.permission.BLUETOOTH_CONNECT
import android.Manifest.permission.POST_NOTIFICATIONS
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine

class MainActivity : ComponentActivity() {

  private val viewModel: LiveViewModel by viewModels()

  // Android runtime permission needed before the SDK can talk to the glasses.
  private val bluetoothPermission =
      registerForActivityResult(RequestPermission()) { granted ->
        if (granted) viewModel.initialize(applicationContext) else viewModel.onBluetoothDenied()
      }

  // Glasses camera permission, granted through the Meta AI app.
  private var cameraContinuation: CancellableContinuation<PermissionStatus>? = null
  private val cameraPermission =
      registerForActivityResult(Wearables.RequestPermissionContract()) { result ->
        cameraContinuation?.resume(result.getOrDefault(PermissionStatus.Denied))
        cameraContinuation = null
      }

  private suspend fun requestCameraPermission(): PermissionStatus =
      suspendCancellableCoroutine { continuation ->
        cameraContinuation = continuation
        continuation.invokeOnCancellation { cameraContinuation = null }
        cameraPermission.launch(Permission.CAMERA)
      }

  // Android 13+ shows the streaming notification, with its Stop button, only once allowed.
  // Streaming works either way, so the answer doesn't matter here.
  private val notificationPermission =
      registerForActivityResult(RequestPermission()) { viewModel.goLive(::requestCameraPermission) }

  /** Opens the camera; in computer mode, after asking to show notifications the first time. */
  private fun openCamera() {
    val ask =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            viewModel.settings.value.toComputer &&
            ContextCompat.checkSelfPermission(this, POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
    if (ask) notificationPermission.launch(POST_NOTIFICATIONS)
    else viewModel.goLive(::requestCameraPermission)
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge(
        statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
    )
    setContent {
      LiveScreen(
          viewModel = viewModel,
          onGrantBluetooth = ::ensureBluetoothPermission,
          onConnect = { Wearables.startRegistration(this) },
          onOpenCamera = ::openCamera,
          onUpdateGlasses = {
            Wearables.openDATGlassesAppUpdate(this).onFailure { error, _ ->
              viewModel.showMessage("Couldn't open Meta AI ($error). Update from its settings.")
            }
          },
      )
    }
  }

  override fun onStart() {
    super.onStart()
    ensureBluetoothPermission()
  }

  override fun onStop() {
    super.onStop()
    // Folding, unfolding or rotating can recreate the activity; that isn't leaving the app.
    if (!isChangingConfigurations) viewModel.onBackground()
  }

  private fun ensureBluetoothPermission() {
    // BLUETOOTH_CONNECT is a runtime permission only on Android 12+; earlier versions grant
    // Bluetooth access at install time.
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        ContextCompat.checkSelfPermission(this, BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED) {
      viewModel.initialize(applicationContext)
    } else {
      bluetoothPermission.launch(BLUETOOTH_CONNECT)
    }
  }
}
