package com.example.glassesview

import android.Manifest.permission.BLUETOOTH_CONNECT
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
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
    enableEdgeToEdge()
    setContent {
      LiveScreen(
          viewModel = viewModel,
          onGrantBluetooth = ::ensureBluetoothPermission,
          onConnect = { Wearables.startRegistration(this) },
          onGoLive = { viewModel.goLive(::requestCameraPermission) },
      )
    }
  }

  override fun onStart() {
    super.onStart()
    ensureBluetoothPermission()
  }

  private fun ensureBluetoothPermission() {
    if (ContextCompat.checkSelfPermission(this, BLUETOOTH_CONNECT) ==
        PackageManager.PERMISSION_GRANTED) {
      viewModel.initialize(applicationContext)
    } else {
      bluetoothPermission.launch(BLUETOOTH_CONNECT)
    }
  }
}
