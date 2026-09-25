package com.example.glassesview

import android.Manifest.permission.BLUETOOTH_CONNECT
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
          onGoLive = { viewModel.goLive(::requestCameraPermission) },
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
