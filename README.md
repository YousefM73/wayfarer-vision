# Glasses View

A minimal Android app that shows the live camera feed from Ray-Ban Meta glasses (Gen 1 / Gen 2),
using Meta's Wearables Device Access Toolkit (`com.meta.wearable:mwdat-*:1.0.0`, Maven Central).

## Before you run it

1. Phone: Android 12+ (minSdk 31) with the **Meta AI** app installed and your glasses paired.
2. In Meta AI, turn on **Developer Mode** for the glasses. The app then works with the default
   placeholder credentials (`0`); no Developer Center setup is needed for local testing.
3. Open this folder in Android Studio and run the `app` configuration.

## Flow

Connect → approve in Meta AI → **Go live** → allow camera access in Meta AI (first time only) →
the feed fills the screen. Tap the square button to stop. A tap on the glasses' touchpad pauses
the stream on the device side; the app shows *PAUSED* until it resumes.

## Tuning

`LiveViewModel.kt`: `QUALITY` (LOW 360×640, MEDIUM 504×896, HIGH 720×1280) and `FPS`
(2, 7, 15, 24, 30). Lower settings usually look smoother over Bluetooth.

## Publishing

For a release build, register the app in the Wearables Developer Center and add to
`local.properties`:

```
mwdat_application_id=YOUR_APP_ID
mwdat_client_token=YOUR_CLIENT_TOKEN
```

## Files

- `MainActivity.kt`: Bluetooth permission, Meta AI registration and camera-permission hand-offs
- `LiveViewModel.kt`: device session → camera → video stream lifecycle
- `I420Converter.kt`: converts decoded I420 frames to bitmaps
- `LiveScreen.kt`: the whole UI
