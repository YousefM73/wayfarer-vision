# Glasses View

A minimal Android app that shows the live camera feed from Ray-Ban Meta glasses (Gen 1 / Gen 2),
using Meta's Wearables Device Access Toolkit (`com.meta.wearable:mwdat-*:1.0.0`, Maven Central).

## Before you run it

1. Phone: Android 10+ (minSdk 29) with the **Meta AI** app installed and your glasses paired.
2. In Meta AI, turn on **Developer Mode** for the glasses. The app then works with the default
   empty placeholder credentials; no Developer Center setup is needed for local testing.
3. Open this folder in Android Studio and run the `app` configuration.

## Flow

Connect → approve in Meta AI → **Go live** → allow camera access in Meta AI (first time only) →
the feed fills the screen. Tap the square button to stop. A tap on the glasses' touchpad pauses
the stream on the device side; the app shows *PAUSED* until it resumes.

If the glasses refuse to stream because Meta's toolkit on them is missing or outdated, the app
shows an **Update glasses** button that opens the right screen in Meta AI. You can also install it
from Meta AI → Settings → App Info while wearing the glasses (Developer Mode must be on).

## How the video gets on screen

1. The SDK streams frames over Bluetooth and decodes them on the phone (I420).
2. Frames arrive in bunches, so a small jitter buffer holds each one until `PLAYOUT_DELAY_MS`
   after its capture time, then releases it. Bursts come out as even motion.
3. At its playback time a frame is converted to a bitmap and drawn onto a `SurfaceView` with a
   hardware canvas, off the main thread.

## Tuning

`LiveViewModel.kt`:

- `PLAYOUT_DELAY_MS` (default 250): higher rides out longer Bluetooth stalls, lower is more live.
- `QUALITY` (LOW 360×640, MEDIUM 504×896, HIGH 720×1280) and `FPS` (2, 7, 15, 24, 30). Lower
  settings travel better over Bluetooth.

The stream is always portrait; the SDK has no landscape option.

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
