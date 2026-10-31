# Audio Video Trimmer

A simple, accessible, and production-ready Android app to trim audio and video files.

**Features**
- Select any audio or video from the device file manager (Storage Access Framework – no storage permission required for picking)
- Play / Pause
- Rewind & Forward with **user-configurable seconds** (1–60)
- Set **Start** and **End** markers while the media is playing
- Trim between the markers (fast, no re-encoding when possible)
- Works on **Android 9 (API 28) → Android 16**
- Fully **screen-reader friendly** (TalkBack content descriptions on every control)
- Clean Material 3 UI
- English UI labels as requested

## How to build APK with GitHub Actions

1. Create a new repository on GitHub.
2. Upload **all** the files from this project (or push via git).
3. Go to the **Actions** tab.
4. Select the workflow **“Build APK”**.
5. Click **Run workflow** → **Run workflow**.
6. When it finishes, download the APK from the **Artifacts** section:
   - `AudioVideoTrimmer-debug` (ready to install)
   - `AudioVideoTrimmer-release-unsigned` (for later signing)

You can also trigger the build automatically on every push to `main` / `master`.

## Local build (optional)

```bash
# Make sure you have Android Studio or JDK 17 + Android SDK
./gradlew assembleDebug
# APK will be at: app/build/outputs/apk/debug/app-debug.apk
```

## Project Structure

```
AudioVideoTrimmer/
├── .github/workflows/build-apk.yml   ← GitHub Actions
├── app/
│   ├── src/main/
│   │   ├── java/.../MainActivity.kt
│   │   ├── java/.../MediaTrimmer.kt  ← Core trimming logic (MediaExtractor + MediaMuxer)
│   │   ├── res/layout/activity_main.xml
│   │   └── AndroidManifest.xml
│   └── build.gradle.kts
├── build.gradle.kts
├── settings.gradle.kts
└── README.md
```

## Technical Notes

- **Playback**: AndroidX Media3 (ExoPlayer) – reliable for both audio & video.
- **Trimming**: Pure Android APIs (`MediaExtractor` + `MediaMuxer`). No FFmpeg, no large native libraries → small APK and good compatibility.
- **Permissions**: Only uses Storage Access Framework for input. Output is written via MediaStore (appears in Gallery / Files app under Movies or Music / AudioVideoTrimmer).
- **Accessibility**: Every button and important view has a proper `contentDescription`.

## Limitations (honest)

- Trimming is sample-accurate (keyframe based for video). Very short cuts near keyframes may have a small offset. This is normal without re-encoding.
- Some exotic codecs may not be supported by the device’s MediaMuxer.
- The app is portrait-only for simplicity and better accessibility.

---

Made for easy learning and real-world use on Android.
