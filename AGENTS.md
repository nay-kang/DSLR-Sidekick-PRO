# DSLR Sidekick PRO — AGENTS.md

## Project

Single-module Android app (`:app`) — Kotlin + JNI C++ — that controls DSLR cameras over USB via libgphoto2 (PTP2 driver) + libusb. Also embeds a NanoHTTPD web server for WiFi photo gallery.

Supported cameras: Nikon (0x04B0), Canon (0x04A9), Sony (0x04CB), Fuji (0x0403). Also auto-detects any PTP class device.

## Source layout

```
app/src/main/java/net/codeedu/dslrsidekickpro/
  GalleryActivity.kt        — Launcher activity, grid gallery
  ViewerActivity.kt          — Full-screen viewer with face detection / focus-check crop (renamed from `MainActivity.kt`; `MainActivity` kept as deprecated subclass)
  CameraService.kt           — Foreground service: USB camera control, photo sync, event polling
  PhotoWebServerService.kt   — Foreground service: NanoHTTPD on port 8080, SSE, thumbnails
  PhotoAdapter.kt            — RecyclerView adapter (Glide, 0.1x size multiplier)
  PhotoPagerAdapter.kt       — ViewPager2 adapter with ForceLandscapeTransformation (rotates portrait images 90° via a small Glide BitmapTransformation; applied to both thumbnail and main requests)
  AppGlideModule.kt          — Glide KSP app module
  AppLogger.kt               — Sentry helper for captured/caught exceptions, breadcrumbs, and manual events

app/src/main/cpp/
  native-lib.cpp             — 7 JNI functions (connect, disconnect, poll, list folders/files, download)
  CMakeLists.txt             — Compiles libusb + libgphoto2 from external/ sources
  config.h                   — libgphoto2 compile-time config (HAVE_LIBUSB1, CAMLIBS_STATIC, no libexif/libjpeg)
  ltdl_stub.c / ltdl.h      — Fake ltdl for static driver linking (only registers PTP2)

external/
  libusb/ libgphoto2/ libexif/   — Vendored upstream sources, compiled via CMake
```

## Build

- Gradle 9.2.0 / AGP 9.2.0 / KSP 2.3.2
- compileSdk 37 (Android 16 DP), minSdk 24, targetSdk 34
- CMake 3.22.1 via NDK, ABIs: arm64-v8a + armeabi-v7a
- `./gradlew assembleDebug` or `./gradlew build`
- NDK + SDK path in `local.properties`
- Sentry DSN is read from ignored `local.properties` key `SENTRY_DSN` and injected into `AndroidManifest.xml` via manifest placeholders
- No lint, no typecheck, no formatter configured

## Key quirks

- **compileSdk 37** is a pre-release Android SDK — build may need preview SDK installed. AGP 9.2.0 targets Java 11.
- **Native code is Linux-only** — libusb uses linux_usbfs.c + linux_netlink.c. Passes `_GNU_SOURCE` and `-pthread`.
- **Only PTP2 driver** — `ltdl_stub.c` maps `gp_port_library_operations` and `camera_init` to static PTP2 symbols. Adding new camera drivers requires extending the stub.
- **Camera disconnect via null-port hack** (`cam->port = nullptr` in `native-lib.cpp:198`) — prevents libusb crash on cable unplug. Memory-safe for production but leaves a tiny leak.
- **USB device detection** — `device_filter.xml` is strict (4 vendor IDs) but `CameraService.kt:144` also detects PTP class (6) and unknown brands as fallback.
- **ProGuard disabled** for release builds (`isMinifyEnabled = false`).
- **Tests** — only boilerplate `ExampleUnitTest` / `ExampleInstrumentedTest`. No real test suite.
- **Glide version note**: `glide = 5.0.5` but `glideKsp = 5.0.7` in `libs.versions.toml`.
- **PhotoWebServer** serves gallery from `assets/gallery.html` + `assets/js/gallery.js`. Thumbnails are center-cropped 200x200 JPEG at 75% quality.
- **Foreground service** permissions: `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_DATA_SYNC` on Android 14+.
- **Sentry** uses `io.sentry:sentry-android` with manifest auto-init (no custom `Application` class). `io.sentry.dsn` and `io.sentry.environment` are Gradle manifest placeholders; debug builds set environment to `debug`, release/default to `production`. Uncaught exceptions are captured automatically; caught exceptions should use `AppLogger.e(...)` when they are important enough to report.
- **Portrait→Landscape transform**: `PhotoPagerAdapter.kt` contains a private Glide `BitmapTransformation` (`ForceLandscapeTransformation`) that rotates portrait bitmaps 90° at decode time. Glide still applies EXIF orientation first; the transformation is applied to both the thumbnail and main requests. This is implemented inline in the adapter (simple rotation via `Matrix`) rather than a separate utility class.
- **Version**: v0.0.3 (versionCode 3)
