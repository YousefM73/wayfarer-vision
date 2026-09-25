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
2. Frames arrive in bunches, so a small jitter buffer holds each one until `PLAYOUT_DELAY_MS`
   after its capture time, then releases it. Bursts come out as even motion.
3. At its playback time a frame is converted to a bitmap and drawn onto a `SurfaceView` with a
   hardware canvas, off the main thread.

## Settings

Chosen on the camera screen and remembered between launches; they apply when the camera opens.

- **Quality**: 360p, 504p (default) or 720p (360×640, 504×896, 720×1280).
- **Frame rate**: 15, 24 (default) or 30 fps.
- **Buffer**: 100, 200 (default) or 300 ms. Smaller is closer to real time; larger rides out
  longer Bluetooth stalls.

The stream is always portrait; the SDK has no landscape option.

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
