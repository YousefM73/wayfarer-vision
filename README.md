# Glasses View

A minimal Android app that shows the live camera feed from Ray-Ban Meta glasses (Gen 1 / Gen 2),
using Meta's Wearables Device Access Toolkit (`com.meta.wearable:mwdat-*:1.0.0`, Maven Central).

## Before you run it

1. Phone: Android 10+ (minSdk 29) with the **Meta AI** app installed and your glasses paired.
2. In Meta AI, turn on **Developer Mode** for the glasses. The app then works with the default
   empty placeholder credentials; no Developer Center setup is needed for local testing.
3. Open this folder in Android Studio and run the `app` configuration.

## Flow

Connect → approve in Meta AI → pick quality, frame rate and buffer → **Open camera** → allow
camera access in Meta AI (choose *Always allow* so it doesn't ask again) → the viewfinder shows
the glasses' view. Tap the square button to close the camera; the connection to the glasses stays
open, so reopening (with new settings) is immediate. A tap on the glasses' touchpad pauses the
stream on the device side; the app shows *PAUSED* until it resumes.

If the glasses refuse to stream because Meta's toolkit on them is missing or outdated, the app
shows an **Update glasses** button that opens the right screen in Meta AI. You can also install it
from Meta AI → Settings → App Info while wearing the glasses (Developer Mode must be on).

## How the video gets on screen

1. The SDK streams frames over Bluetooth and decodes them on the phone (I420).
2. Frames arrive in bunches, so a small jitter buffer holds each one until the chosen buffer time
   after it would have arrived on the fastest recent delivery, then releases it. Bursts come out
   as even motion, and after a Bluetooth stall the delay drops back instead of staying high.
3. At its playback time a frame is converted to a bitmap and drawn onto a `SurfaceView` with a
   hardware canvas, off the main thread.

## Settings

Chosen on the camera screen and remembered between launches; they apply when the camera opens.

- **Quality**: 360p, 504p (default) or 720p (360×640, 504×896, 720×1280).
- **Frame rate**: 15, 24 (default) or 30 fps.
- **Buffer**: 100, 200 (default) or 300 ms. Smaller is closer to real time; larger rides out
  longer Bluetooth stalls. Total glasses-to-screen delay is the buffer plus ~300 ms for the
  glasses, Bluetooth and decoding (measured ~0.6 s at 300 ms on a Galaxy Note 9).

The stream is always portrait; the SDK has no landscape option. The app itself isn't locked
to portrait: on a foldable or tablet the video is centred at 9:16 in any orientation, the settings
sit beside the shutter button on wide screens, and folding, unfolding or rotating mid-stream
keeps the camera open. Tested on a Galaxy Z Fold3 (Android 15).

## Streaming to a computer

Set **Show on** to **Computer** and open the camera. The phone then listens on TCP port 5000 and
shows its address; the glasses start streaming when a player connects and stop when it
disconnects, so every connection begins cleanly with a keyframe. The glasses' compressed HEVC
stream is passed on unchanged (raw Annex-B), paced by the same jitter buffer, so it costs the
phone almost nothing and runs at ~0.5 Mbit/s.

- **Same Wi-Fi network:** use the address the app shows, e.g. `tcp://192.168.1.23:5000`.
- **USB:** with USB debugging on, run `adb forward tcp:5000 tcp:5000` on the computer, then use
  `tcp://127.0.0.1:5000`. No network needed.

Play it with ffplay (`-framerate` should match the frame rate you picked):

```
ffplay -fflags nobuffer -flags low_delay -framerate 24 -f hevc -i tcp://192.168.1.23:5000
```

or mpv (`mpv --profile=low-latency --untimed --demuxer-lavf-format=hevc tcp://192.168.1.23:5000`),
or OBS: add a Media Source, untick *Local File*, input `tcp://192.168.1.23:5000`, input format
`hevc`. Keep the app in the foreground on the phone; leaving it closes the stream.

## Smoother video: turn off background Bluetooth scanning

Phones scan for nearby Bluetooth devices in the background, and while the radio listens the
glasses have to wait. On a Galaxy Note 9 this caused 13–17 stalls of up to ~400 ms every 5
seconds; with scanning off it dropped to 2–8, mostly under 200 ms. Turn off:

- Settings → Google → Device connections → Devices → **Scan for nearby devices**
- Settings → Connections → More connection settings → **Nearby device scanning** (Samsung)
- Settings → Location → Improve accuracy → **Bluetooth scanning**

Wi-Fi on 5 GHz doesn't interfere; 2.4 GHz Wi-Fi shares the radio with Bluetooth and can.

## Publishing

For a release build, register the app in the Wearables Developer Center and add to
`local.properties`:

```
mwdat_application_id=YOUR_APP_ID
mwdat_client_token=YOUR_CLIENT_TOKEN
```

## Files

- `MainActivity.kt`: Bluetooth permission, Meta AI registration and camera-permission hand-offs
- `LiveViewModel.kt`: device session → camera → video stream lifecycle, and the jitter buffer
- `I420Converter.kt`: converts decoded I420 frames to bitmaps
- `LiveScreen.kt`: the whole UI, including drawing frames onto the video surface
- `StreamServer.kt`: serves the compressed stream over TCP in computer mode
