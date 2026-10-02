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

1. The SDK streams frames over Bluetooth and decodes them on the phone (I420). Each frame is
   copied inside the SDK's own callback (the collector runs unconfined): the SDK reuses its
   buffer for the next frame as soon as the callback returns, and a late copy picks up the wrong
   frame's bytes.
2. Frames arrive in bunches, so a small jitter buffer holds each one until the chosen buffer time
   after it would have arrived on the fastest recent delivery, then releases it. Bursts come out
   as even motion, and after a Bluetooth stall the delay drops back instead of staying high.
3. At its playback time a frame is converted to a bitmap and drawn onto a `SurfaceView` with a
   hardware canvas, off the main thread.

## Settings

Chosen on the camera screen and remembered between launches; they apply when the camera opens.

- **Quality**: 360p, 504p (default) or 720p (360×640, 504×896, 720×1280).
- **Frame rate**: 2, 7, 15, 24 (default) or 30 fps. Lower rates keep the glasses cooler.
- **Buffer**: how long frames are held to even out Bluetooth stalls. **Auto** (default) sizes
  it from the link: it watches how late recent frames arrive and uses the smallest delay that
  covers 97% of them, growing at once when stalls get longer and shrinking slowly when the link
  clears. The viewfinder shows the value in use. Or fix it at 0, 100, 200, 300 or 500 ms:
  smaller is closer to real time, larger rides out longer stalls, and 0 passes frames straight
  through, bunched as they arrive. Total glasses-to-screen delay is the buffer plus ~300 ms for
  the glasses, Bluetooth and decoding (measured ~0.6 s at 300 ms on a Galaxy Note 9).
- **Show on**: this phone (default) or a computer, see [Streaming to a computer](#streaming-to-a-computer).
- **Tracking**: off (default) or on, see [Tracking](#tracking). Greyed out until the MultiSet
  keys and a map code are in settings.

The stream is always portrait; the SDK has no landscape option. The app itself isn't locked
to portrait: on a foldable or tablet the video is centred at 9:16 in any orientation, the settings
sit beside the shutter button on wide screens, and folding, unfolding or rotating mid-stream
keeps the camera open. Tested on a Galaxy Z Fold3 (Android 15).

## Streaming to a computer

Set **Show on** to **Computer** and open the camera. The phone then runs a small RTSP server on
port 8554 and shows its address; the glasses start streaming when a player presses play and stop
when it disconnects, so every session begins cleanly with a keyframe. The glasses' compressed
HEVC stream is passed on unchanged as RTP (RFC 7798), paced by the same jitter buffer, so it
costs the phone almost nothing and runs at ~0.5 Mbit/s.

- **Same Wi-Fi network:** use the address the app shows, e.g. `rtsp://192.168.1.23:8554/live`.
- **USB:** with USB debugging on, run `adb forward tcp:8554 tcp:8554` on the computer, then use
  `rtsp://127.0.0.1:8554/live`. No network needed.

Players:

- **VLC:** Media > Open Network Stream, paste the address. With VLC's default settings it
  plays cleanly at 24 fps. For less delay set Tools > Preferences > All > Input / Codecs >
  Video codecs > FFmpeg > *Threads* to 1 and lower *Caching* (under *Show more options* when
  opening the stream) from 1000 ms to 500 ms. VLC's decoder otherwise holds back about 9 frames
  (0.4 s at 24 fps, 1.3 s at 7 fps); when that exceeds the cache every picture arrives "too
  late" and playback stutters, which is what happens at 7 fps with the defaults (Threads 1 fixes it).
- **OBS:** add a Media Source, untick *Local File*, paste the address as the input, leave the
  input format empty, and set *Network Buffering* to 0 MB.
- **ffplay:** `ffplay -fflags nobuffer -flags low_delay rtsp://192.168.1.23:8554/live`

Leave the phone's Buffer on Auto for any player. Measured with VLC on a link with frequent
Bluetooth stalls (24 fps, late pictures per 20 s): Auto with VLC's defaults 0; Auto with Threads
1 and Caching 500 ms 0; Auto with Caching 250 ms 5; Buffer 0 with VLC's defaults 40.

RTP is carried over the RTSP connection itself (TCP); a player that asks for UDP is told to
retry over TCP, which VLC, OBS and ffmpeg do on their own. One player at a time. Frames are never
dropped from the middle of the stream, since each depends on the one before; if a player falls
behind, the server skips ahead to the next keyframe (the glasses send one every 3 seconds at 24 fps). Keep
the app in the foreground on the phone; leaving it closes the stream.

## Tracking

With **Tracking** on, the viewfinder shows where the glasses are and which way they face inside
a space you have mapped with [MultiSet](https://www.multiset.ai). Every few seconds the newest
frame goes to MultiSet's visual positioning API, which matches it against the map and returns
the camera's pose there:

- **Position** in metres from the map's origin (right-handed, Y up), with the match confidence.
- **Heading** as yaw, pitch and roll in degrees: turned right of the map's −Z axis, looking up,
  and tilted toward the right shoulder are positive. The line ends in "· map" because it comes
  from the map fix and so updates only as often as that does.

Setup, under the cog on the camera screen:

1. Map the space with MultiSet and wait for the map to become active. Scanning with their app
   needs a LiDAR iPhone or iPad; scans from other hardware can be uploaded.
2. Create a credential at developer.multiset.ai under Credentials. The Query scope is enough.
3. Import the credentials CSV the portal lets you download, or type in the Client ID and Client
   secret. Then enter the map's code (`MAP_...`, or `MSET_...` for a set of maps).
4. Pick how often to update: every 1, 2, 5 (default) or 10 seconds, counted from the start of
   one query to the start of the next. MultiSet puts a query at about a second, so 1 s means
   one straight after another.

The keys stay in the app's private storage on the phone, which isn't backed up.

Limits:

- Position needs **Show on: This phone**. In computer mode nothing is decoded on the phone, so
  there is no picture to send.
- Every update is one query against your MultiSet plan: 3,600 an hour at 1 s, 1,800 at 2 s,
  720 at 5 s and 360 at 10 s. After a failed query the app waits at least 5 seconds.
- The app also asks the glasses for their motion sensors (the SDK's beta Motion capability),
  which would give head angles ten times a second. Ray-Ban Meta Gen 2 glasses in Developer Mode
  on SDK 1.0.0 list the capability as available and accept the request, then send no samples,
  so for now the heading comes from the map alone. If samples do arrive they take over the
  heading line; their axis labels are untested.

## Less delay: give Bluetooth a clear radio

The delay the buffer needs is set by how badly the Bluetooth link stalls, and that depends mostly
on what else the phone's radio is doing. On a Galaxy Z Fold3 on 2.4 GHz Wi-Fi, with background
scanning on and two watches connected, a tenth of all frames arrived about 300 ms late or more and
the worst over a second late, so Auto sat at 400–800 ms. Things that share the radio:

- **2.4 GHz Wi-Fi** uses the same radio as Bluetooth, and they take turns. Use a 5 GHz network,
  or turn Wi-Fi off and stream to the computer over USB.
- **Background Bluetooth scanning.** On a Galaxy Note 9, turning it off cut stalls from 13–17
  every 5 seconds (up to ~400 ms) to 2–8, mostly under 200 ms. Turn off:
  - Settings → Google → Device connections → Devices → **Scan for nearby devices**
  - Settings → Connections → More connection settings → **Nearby device scanning** (Samsung)
  - Settings → Location → Improve accuracy → **Bluetooth scanning**
- **Other Bluetooth devices** connected to the phone, such as watches and earbuds.

When the link is poor the glasses also drop to a lower resolution by themselves and later switch
back, and the picture freezes for 0.6–0.8 s at each switch.

## Publishing

For a release build, register the app in the Wearables Developer Center and add to
`local.properties`:

```
mwdat_application_id=YOUR_APP_ID
mwdat_client_token=YOUR_CLIENT_TOKEN
```

## Files

- `MainActivity.kt`: Bluetooth permission, Meta AI registration and camera-permission hand-offs
- `LiveViewModel.kt`: device session → camera → video stream lifecycle, the jitter buffer, and
  tracking
- `I420Converter.kt`: converts decoded I420 frames to bitmaps
- `LiveScreen.kt`: the whole UI, including drawing frames onto the video surface
- `RtspServer.kt`: serves the compressed stream over RTSP in computer mode
- `Tracking.kt`: MultiSet position queries, and head angles from a map fix or motion samples
- `CredentialCsv.kt`: reads the credentials CSV from MultiSet's portal
