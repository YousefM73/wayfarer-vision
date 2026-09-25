package com.example.glassesview

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

private val Dim = Color.White.copy(alpha = 0.6f)
private val LiveRed = Color(0xFFFF3B30)

@Composable
fun LiveScreen(
    viewModel: LiveViewModel,
    onGrantBluetooth: () -> Unit,
    onConnect: () -> Unit,
    onGoLive: () -> Unit,
) {
  val ui by viewModel.ui.collectAsStateWithLifecycle()

  MaterialTheme(colorScheme = darkColorScheme()) {
    Box(Modifier.fillMaxSize().background(Color.Black)) {
      ui.frame?.let { frame ->
        Image(
            bitmap = frame.asImageBitmap(),
            contentDescription = "Live view from glasses",
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
        )
      }

      when (val phase = ui.phase) {
        Phase.Live,
        Phase.Paused -> LiveOverlay(paused = phase == Phase.Paused, onStop = viewModel::stop)
        else -> SetupContent(phase, ui.bluetoothDenied, onGrantBluetooth, onConnect, onGoLive)
      }

      ui.message?.let {
        Text(
            text = it,
            color = Dim,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
            modifier =
                Modifier.align(Alignment.BottomCenter)
                    .systemBarsPadding()
                    .padding(start = 24.dp, end = 24.dp, bottom = 120.dp),
        )
      }
    }
  }
}

@Composable
private fun SetupContent(
    phase: Phase,
    bluetoothDenied: Boolean,
    onGrantBluetooth: () -> Unit,
    onConnect: () -> Unit,
    onGoLive: () -> Unit,
) {
  Column(
      modifier = Modifier.fillMaxSize().systemBarsPadding().padding(32.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.Center,
  ) {
    when (phase) {
      Phase.NeedsBluetooth ->
          if (bluetoothDenied) {
            Hint("Bluetooth access is needed to reach your glasses.")
            PillButton("Allow Bluetooth", onGrantBluetooth)
          } else {
            CircularProgressIndicator(color = Color.White)
          }
      Phase.NeedsRegistration -> {
        Hint("Link this app to your glasses through Meta AI.")
        PillButton("Connect", onConnect)
      }
      Phase.Registering -> Waiting("Finish linking in Meta AI…")
      Phase.WaitingForGlasses -> Waiting("Waiting for glasses…\nTurn them on and keep them nearby.")
      Phase.Connecting -> Waiting("Connecting…")
      Phase.Ready -> {
        GoLiveButton(onGoLive)
        Spacer(Modifier.height(20.dp))
        Text("Go live", color = Dim, fontSize = 14.sp)
      }
      Phase.Live,
      Phase.Paused -> Unit
    }
  }
}

@Composable
private fun LiveOverlay(paused: Boolean, onStop: () -> Unit) {
  // Keep the screen awake while watching.
  val view = LocalView.current
  DisposableEffect(Unit) {
    view.keepScreenOn = true
    onDispose { view.keepScreenOn = false }
  }

  Box(Modifier.fillMaxSize().systemBarsPadding()) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier.align(Alignment.TopStart)
                .padding(16.dp)
                .clip(RoundedCornerShape(50))
                .background(Color.Black.copy(alpha = 0.45f))
                .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
      Box(Modifier.size(8.dp).clip(CircleShape).background(if (paused) Dim else LiveRed))
      Spacer(Modifier.width(6.dp))
      Text(
          if (paused) "PAUSED" else "LIVE",
          color = Color.White,
          fontSize = 12.sp,
          fontWeight = FontWeight.SemiBold,
          letterSpacing = 1.sp,
      )
    }

    // Stop: white ring with a red square, like a recorder.
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            Modifier.align(Alignment.BottomCenter)
                .padding(bottom = 32.dp)
                .size(64.dp)
                .clip(CircleShape)
                .border(3.dp, Color.White, CircleShape)
                .clickable(onClick = onStop),
    ) {
      Box(Modifier.size(22.dp).clip(RoundedCornerShape(5.dp)).background(LiveRed))
    }
  }
}

@Composable
private fun GoLiveButton(onClick: () -> Unit) {
  Box(
      contentAlignment = Alignment.Center,
      modifier =
          Modifier.size(84.dp)
              .clip(CircleShape)
              .border(3.dp, Color.White, CircleShape)
              .clickable(onClick = onClick),
  ) {
    Box(Modifier.size(64.dp).clip(CircleShape).background(LiveRed))
  }
}

@Composable
private fun Hint(text: String) {
  Text(text, color = Dim, fontSize = 15.sp, textAlign = TextAlign.Center)
  Spacer(Modifier.height(24.dp))
}

@Composable
private fun Waiting(text: String) {
  CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(28.dp))
  Spacer(Modifier.height(20.dp))
  Text(text, color = Dim, fontSize = 15.sp, textAlign = TextAlign.Center)
}

@Composable
private fun PillButton(label: String, onClick: () -> Unit) {
  Button(
      onClick = onClick,
      shape = RoundedCornerShape(50),
      colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
  ) {
    Text(label, fontWeight = FontWeight.Medium, modifier = Modifier.padding(horizontal = 12.dp))
  }
}
