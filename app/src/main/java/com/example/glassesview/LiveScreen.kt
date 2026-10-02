package com.example.glassesview

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import android.view.Surface
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.AndroidExternalSurface
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Dim = Color.White.copy(alpha = 0.6f)
private val Faint = Color.White.copy(alpha = 0.35f)
private val Scrim = Color.Black.copy(alpha = 0.45f)
private val Streaming = Color(0xFF34C759)
private val Held = Color(0xFFFFB020)

// A credentials file is a few hundred characters; anything longer was picked by mistake.
private const val MAX_CSV_CHARS = 16 * 1024

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
  val multiSet by viewModel.multiSet.collectAsStateWithLifecycle()
  // Saveable, so a file picked for import still has a settings screen to come back to.
  var showSettings by rememberSaveable { mutableStateOf(false) }

  MaterialTheme(colorScheme = darkColorScheme()) {
    Box(Modifier.fillMaxSize().background(Color.Black)) {
      // The camera view: a 9:16 frame (the glasses' feed is always portrait) between the system
      // bars. Frames are drawn onto the surface with a hardware canvas, off the main thread, so
      // the UI never redraws per frame; the viewfinder overlay sits on the same frame.
      if (ui.streaming != null && !ui.toComputer) {
        Box(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.Center) {
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
              Viewfinder(
                  settings,
                  viewModel.bufferNow,
                  paused = ui.phase == Phase.Paused,
                  tracking = if (ui.tracking) viewModel.tracking else null,
                  onClose = viewModel::stop,
              )
            }
          }
        }
      }

      // Settings are reachable whenever the camera is closed; the keys are read when it opens.
      val idle = ui.streaming == null
      val inSettings = showSettings && idle
      if (inSettings) {
        BackHandler { showSettings = false }
        ApiSettings(
            config = multiSet,
            onChange = viewModel::updateMultiSet,
            onDone = { showSettings = false },
        )
      } else {
        when (val phase = ui.phase) {
          Phase.Listening,
          Phase.Live,
          Phase.Paused ->
              if (ui.toComputer) {
                ComputerStatus(
                    ui,
                    settings,
                    viewModel.bufferNow,
                    viewModel.tracking,
                    onClose = viewModel::stop,
                )
              }
          Phase.Ready ->
              CameraStart(
                  settings = settings,
                  trackingAvailable = multiSet.complete,
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
        if (idle) {
          SettingsCog(
              onClick = { showSettings = true },
              modifier = Modifier.align(Alignment.TopEnd).safeDrawingPadding().padding(8.dp),
          )
        }
      }

      ui.message?.takeUnless { inSettings }?.let {
        Text(
            text = it,
            color = Dim,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
            modifier =
                Modifier.align(Alignment.TopCenter)
                    .safeDrawingPadding()
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
      modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(32.dp),
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
        Hint(
            "Your glasses didn't answer. Put them on and try again, or restart them with their " +
                "power switch. If it keeps happening, reinstall Meta's toolkit from Meta AI.")
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
      Phase.Listening,
      Phase.Live,
      Phase.Paused -> Unit
    }
  }
}

/** The idle camera: a shutter-style button to open it, with stream settings below or beside. */
@Composable
private fun CameraStart(
    settings: StreamSettings,
    trackingAvailable: Boolean,
    onChange: ((StreamSettings) -> StreamSettings) -> Unit,
    onOpen: () -> Unit,
) {
  BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
    val content = Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 24.dp)
    if (maxWidth > maxHeight) {
      // Wide screen (a fold held sideways, or a tablet): shutter left, settings right.
      Row(content, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { Shutter(onOpen) }
        Box(Modifier.weight(1.4f), contentAlignment = Alignment.Center) {
          SettingsPanel(settings, trackingAvailable, onChange)
        }
      }
    } else {
      Column(content, horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.weight(1f))
        Shutter(onOpen)
        Spacer(Modifier.weight(1f))
        SettingsPanel(settings, trackingAvailable, onChange)
      }
    }
  }
}

@Composable
private fun Shutter(onOpen: () -> Unit) {
  Column(horizontalAlignment = Alignment.CenterHorizontally) {
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
  }
}

@Composable
private fun SettingsPanel(
    settings: StreamSettings,
    trackingAvailable: Boolean, // false until the MultiSet keys and map code are in settings
    onChange: ((StreamSettings) -> StreamSettings) -> Unit,
) {
  // Capped so the rows don't stretch across a wide unfolded screen.
  BoxWithConstraints(Modifier.widthIn(max = 480.dp)) {
    // On a narrow screen (a fold's cover screen) the label sits above its chips, not beside them.
    val stacked = maxWidth < 330.dp
    Column(verticalArrangement = Arrangement.spacedBy(if (stacked) 14.dp else 12.dp)) {
      SettingRow(
          title = "Quality",
          options = StreamSettings.QUALITIES.keys.toList(),
          selected = settings.quality,
          label = { "${StreamSettings.QUALITIES.getValue(it).first}p" },
          onSelect = { q -> onChange { it.copy(quality = q) } },
          stacked = stacked,
      )
      SettingRow(
          title = "Frame rate",
          options = StreamSettings.FRAME_RATES,
          selected = settings.fps,
          label = { it.toString() },
          onSelect = { fps -> onChange { it.copy(fps = fps) } },
          stacked = stacked,
      )
      SettingRow(
          title = "Buffer (ms)",
          options = StreamSettings.BUFFERS,
          selected = settings.bufferMs,
          label = { if (it == StreamSettings.AUTO_BUFFER) "Auto" else it.toString() },
          onSelect = { ms -> onChange { it.copy(bufferMs = ms) } },
          stacked = stacked,
      )
      SettingRow(
          title = "Show on",
          options = listOf(false, true),
          selected = settings.toComputer,
          label = { if (it) "Computer" else "This phone" },
          onSelect = { pc -> onChange { it.copy(toComputer = pc) } },
          stacked = stacked,
      )
      SettingRow(
          title = "Tracking",
          options = listOf(false, true),
          // Without keys it shows as off, whatever was chosen while they were there.
          selected = settings.tracking && trackingAvailable,
          label = { if (it) "On" else "Off" },
          onSelect = { on -> onChange { it.copy(tracking = on) } },
          stacked = stacked,
          enabled = trackingAvailable,
      )
      if (!trackingAvailable) {
        Text(
            "Tracking needs MultiSet keys and a map code. Add them under the cog.",
            color = Faint,
            fontSize = 12.sp,
        )
      }
      Text(
          "Lower frame rate and quality keep the glasses cooler.",
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
    stacked: Boolean,
    enabled: Boolean = true,
) {
  // A setting that can't be changed right now is greyed out as a whole.
  val row = Modifier.fillMaxWidth().alpha(if (enabled) 1f else 0.38f)
  if (stacked) {
    Column(row) {
      Text(title, color = Dim, fontSize = 13.sp, modifier = Modifier.padding(bottom = 6.dp))
      Chips(options, selected, label, onSelect, enabled, Modifier.fillMaxWidth())
    }
  } else {
    Row(row, verticalAlignment = Alignment.CenterVertically) {
      BasicText(
          title,
          style = TextStyle(color = Dim, fontSize = 13.sp),
          maxLines = 1,
          autoSize = TextAutoSize.StepBased(minFontSize = 10.sp, maxFontSize = 13.sp),
          modifier = Modifier.width(100.dp),
      )
      Chips(options, selected, label, onSelect, enabled, Modifier.weight(1f))
    }
  }
}

@Composable
private fun <T> Chips(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    enabled: Boolean,
    modifier: Modifier,
) {
  Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = modifier.selectableGroup()) {
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
                      enabled = enabled,
                      role = Role.RadioButton,
                      onClick = { onSelect(option) },
                  )
                  .padding(horizontal = 6.dp, vertical = 8.dp),
      ) {
        // Shrinks rather than wraps when the chip is narrow or the font size is large.
        BasicText(
            label(option),
            style =
                TextStyle(
                    color = if (isSelected) Color.Black else Color.White,
                    fontSize = 13.sp,
                    fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal,
                    textAlign = TextAlign.Center,
                ),
            maxLines = 1,
            autoSize = TextAutoSize.StepBased(minFontSize = 9.sp, maxFontSize = 13.sp),
        )
      }
    }
  }
}

/** Overlay on the camera frame: framing marks, status and specs, and a close control. */
@Composable
private fun Viewfinder(
    settings: StreamSettings,
    bufferNow: StateFlow<Long>,
    paused: Boolean,
    tracking: StateFlow<TrackingState>?,
    onClose: () -> Unit,
) {
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
      Column(
          Modifier.align(Alignment.TopStart).fillMaxWidth(),
          verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
          if (maxWidth < 340.dp) {
            // Narrow frame (a fold's cover screen, or unfolded and held sideways): stack the pills.
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
              StatusPill(paused)
              SpecPill(settings, bufferNow)
            }
          } else {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
              StatusPill(paused)
              Spacer(Modifier.weight(1f))
              SpecPill(settings, bufferNow)
            }
          }
        }
        if (tracking != null) TrackingReadout(tracking)
      }

      CloseButton(onClose, Modifier.align(Alignment.BottomCenter))
    }
  }
}

/** Close: white ring with a white square. */
@Composable
private fun CloseButton(onClose: () -> Unit, modifier: Modifier = Modifier) {
  Box(
      contentAlignment = Alignment.Center,
      modifier =
          modifier
              .size(60.dp)
              .clip(CircleShape)
              .background(Scrim)
              .border(2.dp, Color.White, CircleShape)
              .clickable(onClickLabel = "Close camera", onClick = onClose),
  ) {
    Box(Modifier.size(18.dp).clip(RoundedCornerShape(4.dp)).background(Color.White))
  }
}

/** Status while the stream is served to a computer instead of drawn here. */
@Composable
private fun ComputerStatus(
    ui: LiveUiState,
    settings: StreamSettings,
    bufferNow: StateFlow<Long>,
    tracking: StateFlow<TrackingState>,
    onClose: () -> Unit,
) {
  // Keep the phone awake: the stream ends if the app leaves the screen.
  val view = LocalView.current
  DisposableEffect(Unit) {
    view.keepScreenOn = true
    onDispose { view.keepScreenOn = false }
  }

  val (dot, title, detail) =
      when {
        ui.phase == Phase.Paused ->
            Triple(Held, "Paused on the glasses", "Tap the glasses' touchpad to resume.")
        ui.clientAddress != null ->
            Triple(Streaming, "Streaming to computer", ui.clientAddress ?: "")
        else ->
            Triple(
                Faint,
                "Waiting for a computer",
                "Open the address below in VLC or OBS. The glasses start streaming when it plays.",
            )
      }
  val wifi = ui.serverAddress
  val port = LiveViewModel.PORT
  Column(
      modifier =
          Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 28.dp, vertical = 24.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Spacer(Modifier.weight(1f))
    Row(verticalAlignment = Alignment.CenterVertically) {
      Box(Modifier.size(10.dp).clip(CircleShape).background(dot))
      Spacer(Modifier.width(10.dp))
      Text(title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Medium)
    }
    Spacer(Modifier.height(8.dp))
    Text(detail, color = Dim, fontSize = 14.sp, textAlign = TextAlign.Center)
    if (ui.tracking) {
      Spacer(Modifier.height(16.dp))
      TrackingReadout(tracking)
    }
    Spacer(Modifier.height(28.dp))
    Column(
        Modifier.fillMaxWidth().widthIn(max = 480.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      AddressLine("Wi-Fi", if (wifi != null) "rtsp://$wifi/live" else "not on a network")
      AddressLine("USB", "adb forward tcp:$port tcp:$port\nrtsp://127.0.0.1:$port/live")
      Text(
          "VLC: Media › Open Network Stream. OBS: Media Source, Local File off.",
          color = Faint,
          fontSize = 12.sp,
          modifier = Modifier.padding(top = 6.dp),
      )
    }
    Spacer(Modifier.weight(1f))
    SpecPill(settings, bufferNow)
    Spacer(Modifier.height(20.dp))
    CloseButton(onClose)
  }
}

@Composable
private fun AddressLine(label: String, value: String) {
  Row(verticalAlignment = Alignment.Top) {
    Text(label, color = Dim, fontSize = 13.sp, modifier = Modifier.width(56.dp))
    // One line per address, shrinking on narrow screens rather than wrapping mid-address.
    Column {
      for (line in value.lines()) {
        BasicText(
            line,
            style =
                TextStyle(
                    color = Color.White, fontSize = 13.sp, fontFamily = FontFamily.Monospace),
            maxLines = 1,
            autoSize = TextAutoSize.StepBased(minFontSize = 9.sp, maxFontSize = 13.sp),
        )
      }
    }
  }
}

/** Head angles and map position. Collected here so only this readout redraws per sample. */
@Composable
private fun TrackingReadout(tracking: StateFlow<TrackingState>) {
  val state by tracking.collectAsStateWithLifecycle()
  val head = state.head
  val facing = state.pose?.facing
  val headLine =
      when {
        head != null && head.hasYaw ->
            "yaw %4.0f°  pitch %4.0f°  roll %4.0f°".format(head.yaw, head.pitch, head.roll)
        head != null -> "pitch %4.0f°  roll %4.0f°".format(head.pitch, head.roll)
        // Nothing from the glasses' sensors; the last map fix also says which way they faced.
        facing != null ->
            "yaw %4.0f°  pitch %4.0f°  roll %4.0f°  ·  map"
                .format(facing.yaw, facing.pitch, facing.roll)
        else -> state.headNote ?: "Head: waiting for the glasses…"
      }
  val poseLine = buildString {
    state.pose?.let { pose ->
      append("x %.2f  y %.2f  z %.2f m".format(pose.x, pose.y, pose.z))
      pose.confidence?.let { append("  ·  %.0f%%".format(it * 100)) }
    }
    state.locationNote?.let {
      if (isNotEmpty()) append("  ·  ")
      append(it)
    }
  }
  Column(
      Modifier.clip(RoundedCornerShape(10.dp))
          .background(Scrim)
          .padding(horizontal = 10.dp, vertical = 6.dp),
  ) {
    for (line in listOf(headLine, poseLine)) {
      if (line.isEmpty()) continue
      BasicText(
          line,
          style = TextStyle(color = Color.White, fontSize = 11.sp, fontFamily = FontFamily.Monospace),
          maxLines = 1,
          autoSize = TextAutoSize.StepBased(minFontSize = 8.sp, maxFontSize = 11.sp),
      )
    }
  }
}

@Composable
private fun StatusPill(paused: Boolean) {
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
}

@Composable
private fun SpecPill(settings: StreamSettings, bufferNow: StateFlow<Long>) {
  val (w, h) = StreamSettings.QUALITIES.getValue(settings.quality)
  val buffer =
      if (settings.bufferMs == StreamSettings.AUTO_BUFFER) {
        // On auto, show the delay the link currently needs.
        val now by bufferNow.collectAsStateWithLifecycle()
        "auto $now ms"
      } else {
        "${settings.bufferMs} ms"
      }
  BasicText(
      "$w×$h · ${settings.fps} fps · $buffer",
      style = TextStyle(color = Dim, fontSize = 11.sp, fontFamily = FontFamily.Monospace),
      maxLines = 1,
      autoSize = TextAutoSize.StepBased(minFontSize = 9.sp, maxFontSize = 11.sp),
      modifier =
          Modifier.clip(RoundedCornerShape(50))
              .background(Scrim)
              .padding(horizontal = 10.dp, vertical = 5.dp),
  )
}

@Composable
private fun SettingsCog(onClick: () -> Unit, modifier: Modifier = Modifier) {
  Box(
      contentAlignment = Alignment.Center,
      modifier =
          modifier.size(48.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onClick),
  ) {
    Icon(
        painterResource(R.drawable.ic_settings),
        contentDescription = "Settings",
        tint = Dim,
        modifier = Modifier.size(22.dp),
    )
  }
}

/** Keys for the services the app calls. Typed in here and kept in the app's private storage. */
@Composable
private fun ApiSettings(
    config: MultiSetConfig,
    onChange: ((MultiSetConfig) -> MultiSetConfig) -> Unit,
    onDone: () -> Unit,
) {
  // Each field holds its own text, starting from what is saved. Sending keystrokes to the
  // ViewModel and reading them back would trail the keyboard and make the cursor jump.
  var clientId by remember { mutableStateOf(config.clientId) }
  var clientSecret by remember { mutableStateOf(config.clientSecret) }
  var mapCode by remember { mutableStateOf(config.mapCode) }
  var showSecret by remember { mutableStateOf(false) }

  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  var importNote by remember { mutableStateOf<String?>(null) }
  val pickCsv =
      rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
          scope.launch {
            val credential = withContext(Dispatchers.IO) { readCredential(context, uri) }
            if (credential == null) {
              importNote = "No client ID and secret found in that file."
            } else {
              clientId = credential.clientId
              clientSecret = credential.clientSecret
              onChange {
                it.copy(clientId = credential.clientId, clientSecret = credential.clientSecret)
              }
              importNote =
                  if (credential.name.isEmpty()) "Imported." else "Imported “${credential.name}”."
            }
          }
        }
      }

  Column(
      modifier =
          Modifier.fillMaxSize()
              .safeDrawingPadding() // includes the keyboard, so the fields scroll clear of it
              .verticalScroll(rememberScrollState())
              .padding(horizontal = 28.dp, vertical = 16.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Column(Modifier.widthIn(max = 480.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Settings", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.weight(1f))
        Text(
            "Done",
            color = Color.White,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            modifier =
                Modifier.clip(RoundedCornerShape(50))
                    .clickable(role = Role.Button, onClick = onDone)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
        )
      }
      Spacer(Modifier.height(4.dp))
      Text("MultiSet", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
      Text(
          "Finds your position in a space you've mapped, while Tracking is on. Create a " +
              "credential at developer.multiset.ai under Credentials, then import the CSV it " +
              "lets you download or type the values in.",
          color = Dim,
          fontSize = 13.sp,
      )
      Text(
          "Import credentials CSV",
          color = Color.White,
          fontSize = 13.sp,
          modifier =
              Modifier.clip(RoundedCornerShape(50))
                  .background(Color.White.copy(alpha = 0.08f))
                  // Any type: file providers disagree about what a .csv is.
                  .clickable(role = Role.Button) { pickCsv.launch(arrayOf("*/*")) }
                  .padding(horizontal = 14.dp, vertical = 9.dp),
      )
      importNote?.let { Text(it, color = Dim, fontSize = 12.sp) }
      KeyField("Client ID", clientId) { text ->
        clientId = text
        onChange { it.copy(clientId = text.trim()) }
      }
      KeyField(
          "Client secret",
          clientSecret,
          hidden = !showSecret,
          trailing = {
            Text(
                if (showSecret) "Hide" else "Show",
                color = Dim,
                fontSize = 13.sp,
                modifier =
                    Modifier.clip(RoundedCornerShape(50))
                        .clickable(role = Role.Button) { showSecret = !showSecret }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
            )
          },
      ) { text ->
        clientSecret = text
        onChange { it.copy(clientSecret = text.trim()) }
      }
      KeyField("Map code", mapCode) { text ->
        mapCode = text
        onChange { it.copy(mapCode = text.trim()) }
      }
      Text(
          "The map code starts with MAP_, or MSET_ for a set of maps. These stay on this phone.",
          color = Faint,
          fontSize = 12.sp,
      )
      Spacer(Modifier.height(4.dp))
      BoxWithConstraints(Modifier.fillMaxWidth()) {
        SettingRow(
            title = "Update every",
            options = MultiSetConfig.INTERVALS,
            selected = config.everySeconds,
            label = { "$it s" },
            onSelect = { seconds -> onChange { it.copy(everySeconds = seconds) } },
            stacked = maxWidth < 330.dp,
        )
      }
      Text(
          "Each update is one query on your MultiSet plan: 3,600 an hour at 1 s, 720 at 5 s. " +
              "A query takes about a second, so 1 s is as fast as it goes.",
          color = Faint,
          fontSize = 12.sp,
      )
    }
  }
}

/** The credential in a picked file, or null when the file can't be read or doesn't hold one. */
private fun readCredential(context: Context, uri: Uri): MultiSetCredential? =
    try {
      context.contentResolver.openInputStream(uri)?.use { input ->
        val reader = input.reader()
        val chars = CharArray(MAX_CSV_CHARS)
        var length = 0
        while (length < chars.size) {
          val read = reader.read(chars, length, chars.size - length)
          if (read < 0) break
          length += read
        }
        parseCredentialCsv(String(chars, 0, length))
      }
    } catch (_: IOException) {
      null
    } catch (_: SecurityException) {
      null
    }

@Composable
private fun KeyField(
    label: String,
    value: String,
    hidden: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
    onValueChange: (String) -> Unit,
) {
  OutlinedTextField(
      value = value,
      onValueChange = onValueChange,
      label = { Text(label) },
      singleLine = true,
      textStyle = TextStyle(fontSize = 14.sp, fontFamily = FontFamily.Monospace),
      visualTransformation =
          if (hidden) PasswordVisualTransformation() else VisualTransformation.None,
      // Keys aren't words: no autocorrect or capitals, and the keyboard doesn't learn the secret.
      keyboardOptions =
          KeyboardOptions(
              capitalization = KeyboardCapitalization.None,
              autoCorrectEnabled = false,
              keyboardType = if (hidden) KeyboardType.Password else KeyboardType.Ascii,
          ),
      trailingIcon = trailing,
      colors =
          OutlinedTextFieldDefaults.colors(
              focusedTextColor = Color.White,
              unfocusedTextColor = Color.White,
              focusedBorderColor = Color.White,
              unfocusedBorderColor = Faint,
              focusedLabelColor = Color.White,
              unfocusedLabelColor = Dim,
              cursorColor = Color.White,
          ),
      modifier = Modifier.fillMaxWidth(),
  )
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
