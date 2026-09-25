package com.example.glassesview

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Rect
import android.view.Surface
import androidx.compose.foundation.AndroidExternalSurface
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val Dim = Color.White.copy(alpha = 0.6f)
private val Faint = Color.White.copy(alpha = 0.35f)
private val Scrim = Color.Black.copy(alpha = 0.45f)
private val Streaming = Color(0xFF34C759)
private val Held = Color(0xFFFFB020)

@Composable
fun LiveScreen(
    viewModel: LiveViewModel,
    onGrantBluetooth: () -> Unit,
    onConnect: () -> Unit,
    onOpenCamera: () -> Unit,
    onUpdateGlasses: () -> Unit,
) {
  val ui by viewModel.ui.collectAsStateWithLifecycle()
  val settings by viewModel.settings.collectAsStateWithLifecycle()

  MaterialTheme(colorScheme = darkColorScheme()) {
    Box(Modifier.fillMaxSize().background(Color.Black)) {
      // The camera view: a 9:16 frame (the glasses' feed is always portrait) between the system
      // bars. Frames are drawn onto the surface with a hardware canvas, off the main thread, so
      // the UI never redraws per frame; the viewfinder overlay sits on the same frame.
      if (ui.streaming != null) {
        Box(Modifier.fillMaxSize().systemBarsPadding(), contentAlignment = Alignment.Center) {
          Box(Modifier.aspectRatio(9f / 16f)) {
            AndroidExternalSurface(modifier = Modifier.fillMaxSize()) {
              onSurface { surface, initialWidth, initialHeight ->
                val dst = Rect(0, 0, initialWidth, initialHeight)
                surface.onChanged { width, height -> dst.set(0, 0, width, height) }
                withContext(Dispatchers.Default) {
                  viewModel.frames.collect { frame ->
                    if (frame != null) drawFrame(surface, frame, dst)
                  }
                }
              }
            }
            if (ui.phase == Phase.Live || ui.phase == Phase.Paused) {
              Viewfinder(settings, paused = ui.phase == Phase.Paused, onClose = viewModel::stop)
            }
          }
        }
      }

      when (val phase = ui.phase) {
        Phase.Live,
        Phase.Paused -> Unit
        Phase.Ready ->
            CameraStart(
                settings = settings,
                onChange = viewModel::updateSettings,
                onOpen = onOpenCamera,
            )
        else ->
            SetupContent(
                phase,
                ui.bluetoothDenied,
                onGrantBluetooth,
                onConnect,
                onOpenCamera,
                onUpdateGlasses,
            )
      }

      ui.message?.let {
        Text(
            text = it,
            color = Dim,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
            modifier =
                Modifier.align(Alignment.TopCenter)
                    .systemBarsPadding()
                    .padding(start = 24.dp, end = 24.dp, top = 64.dp),
        )
      }
    }
  }
}

private val framePaint = Paint(Paint.FILTER_BITMAP_FLAG)

private fun drawFrame(surface: Surface, frame: Bitmap, dst: Rect) {
  val canvas =
      try {
        surface.lockHardwareCanvas()
      } catch (_: RuntimeException) {
        return // surface is going away
      }
  try {
    canvas.drawBitmap(frame, null, dst, framePaint)
  } finally {
    surface.unlockCanvasAndPost(canvas)
  }
}

@Composable
private fun SetupContent(
    phase: Phase,
    bluetoothDenied: Boolean,
    onGrantBluetooth: () -> Unit,
    onConnect: () -> Unit,
    onOpenCamera: () -> Unit,
    onUpdateGlasses: () -> Unit,
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
      Phase.GlassesUpdateRequired -> {
        Hint("Your glasses need Meta's toolkit installed or updated before they can stream.")
        PillButton("Update glasses", onUpdateGlasses)
        Spacer(Modifier.height(12.dp))
        Text(
            "Try again",
            color = Dim,
            fontSize = 14.sp,
            modifier = Modifier.clickable(onClick = onOpenCamera).padding(12.dp),
        )
      }
      Phase.WaitingForGlasses -> Waiting("Waiting for glasses…\nTurn them on and keep them nearby.")
      Phase.Connecting -> Waiting("Starting camera…")
      Phase.Ready,
      Phase.Live,
      Phase.Paused -> Unit
    }
  }
}

/** The idle camera: a shutter-style button to open it, with stream settings below. */
@Composable
private fun CameraStart(
    settings: StreamSettings,
    onChange: ((StreamSettings) -> StreamSettings) -> Unit,
    onOpen: () -> Unit,
) {
  Column(
      modifier = Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 28.dp, vertical = 24.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Spacer(Modifier.weight(1f))
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            Modifier.size(80.dp)
                .clip(CircleShape)
                .border(3.dp, Color.White, CircleShape)
                .clickable(onClickLabel = "Open camera", onClick = onOpen),
    ) {
      Box(Modifier.size(62.dp).clip(CircleShape).background(Color.White))
    }
    Spacer(Modifier.height(14.dp))
    Text("Open camera", color = Dim, fontSize = 14.sp)
    Spacer(Modifier.weight(1f))

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
      SettingRow(
          title = "Quality",
          options = StreamSettings.QUALITIES.keys.toList(),
          selected = settings.quality,
          label = { "${StreamSettings.QUALITIES.getValue(it).first}p" },
          onSelect = { q -> onChange { it.copy(quality = q) } },
      )
      SettingRow(
          title = "Frame rate",
          options = StreamSettings.FRAME_RATES,
          selected = settings.fps,
          label = { "$it fps" },
          onSelect = { fps -> onChange { it.copy(fps = fps) } },
      )
      SettingRow(
          title = "Buffer",
          options = StreamSettings.BUFFERS,
          selected = settings.bufferMs,
          label = { "$it ms" },
          onSelect = { ms -> onChange { it.copy(bufferMs = ms) } },
      )
      Text(
          "A smaller buffer is closer to real time; a larger one rides out Bluetooth hiccups.",
          color = Faint,
          fontSize = 12.sp,
          modifier = Modifier.padding(top = 2.dp),
      )
    }
  }
}

@Composable
private fun <T> SettingRow(
    title: String,
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
) {
  Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
    Text(title, color = Dim, fontSize = 13.sp, modifier = Modifier.width(84.dp))
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.weight(1f).selectableGroup(),
    ) {
      for (option in options) {
        val isSelected = option == selected
        Box(
            contentAlignment = Alignment.Center,
            modifier =
                Modifier.weight(1f)
                    .clip(RoundedCornerShape(50))
                    .background(if (isSelected) Color.White else Color.White.copy(alpha = 0.08f))
                    .selectable(
                        selected = isSelected,
                        role = Role.RadioButton,
                        onClick = { onSelect(option) },
                    )
                    .padding(vertical = 8.dp),
        ) {
          Text(
              label(option),
              color = if (isSelected) Color.Black else Color.White,
              fontSize = 13.sp,
              fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal,
          )
        }
      }
    }
  }
}

/** Overlay on the camera frame: framing marks, status and specs, and a close control. */
@Composable
private fun Viewfinder(settings: StreamSettings, paused: Boolean, onClose: () -> Unit) {
  // Keep the screen awake while watching.
  val view = LocalView.current
  DisposableEffect(Unit) {
    view.keepScreenOn = true
    onDispose { view.keepScreenOn = false }
  }

  Box(Modifier.fillMaxSize()) {
    // Corner marks around the image, like a camera viewfinder.
    Box(
        Modifier.fillMaxSize().padding(14.dp).drawBehind {
          val arm = 22.dp.toPx()
          val stroke = 2.dp.toPx()
          val c = Color.White.copy(alpha = 0.7f)
          val (w, h) = size.width to size.height
          for ((x, dx) in listOf(0f to 1f, w to -1f)) {
            for ((y, dy) in listOf(0f to 1f, h to -1f)) {
              drawLine(c, Offset(x, y), Offset(x + dx * arm, y), stroke)
              drawLine(c, Offset(x, y), Offset(x, y + dy * arm), stroke)
            }
          }
        })

    Box(Modifier.fillMaxSize().padding(horizontal = 26.dp, vertical = 28.dp)) {
      Row(
          verticalAlignment = Alignment.CenterVertically,
          modifier = Modifier.align(Alignment.TopStart).fillMaxWidth(),
      ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier =
                Modifier.clip(RoundedCornerShape(50))
                    .background(Scrim)
                    .padding(horizontal = 10.dp, vertical = 5.dp),
        ) {
          Box(Modifier.size(7.dp).clip(CircleShape).background(if (paused) Held else Streaming))
          Spacer(Modifier.width(6.dp))
          Text(
              if (paused) "PAUSED" else "CAMERA",
              color = Color.White,
              fontSize = 11.sp,
              fontWeight = FontWeight.Medium,
              letterSpacing = 1.2.sp,
          )
        }
        Spacer(Modifier.weight(1f))
        val (w, h) = StreamSettings.QUALITIES.getValue(settings.quality)
        Text(
            "$w×$h · ${settings.fps} fps · ${settings.bufferMs} ms",
            color = Dim,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            modifier =
                Modifier.clip(RoundedCornerShape(50))
                    .background(Scrim)
                    .padding(horizontal = 10.dp, vertical = 5.dp),
        )
      }

      // Close: white ring with a white square.
      Box(
          contentAlignment = Alignment.Center,
          modifier =
              Modifier.align(Alignment.BottomCenter)
                  .size(60.dp)
                  .clip(CircleShape)
                  .background(Scrim)
                  .border(2.dp, Color.White, CircleShape)
                  .clickable(onClickLabel = "Close camera", onClick = onClose),
      ) {
        Box(Modifier.size(18.dp).clip(RoundedCornerShape(4.dp)).background(Color.White))
      }
    }
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
