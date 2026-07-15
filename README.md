# cinematic-scroll-compose

[![](https://jitpack.io/v/Attty/cinematic-scroll-compose.svg)](https://jitpack.io/#Attty/cinematic-scroll-compose)
![minSdk](https://img.shields.io/badge/minSdk-26-blue)
![license](https://img.shields.io/badge/license-MIT-green)

Cinematic scroll scrubbing for Jetpack Compose — the effect big product
landing pages use, as a single composable. Scrolling drives a pre-rendered
frame sequence like a timeline (with lerp smoothing and inertia), and the
gyroscope adds a parallax shift on top. No video player involved at runtime —
just frames, a two-tier cache and math.

<p align="center">
  <img src="docs/demo.gif" width="300" alt="demo" />
</p>

## Installation

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        maven("https://jitpack.io")
    }
}

// build.gradle.kts
dependencies {
    implementation("com.github.Attty:cinematic-scroll-compose:0.1.0")
}
```

## Quick start

Declare content like a `LazyColumn` — one `section` per screen of scroll:

```kotlin
CinematicScroll(source = FrameSource.Assets("frames")) {
    section { relative ->
        Text(
            "HELLO",
            Modifier
                .align(Alignment.Center)
                .graphicsLayer {
                    val rel = relative()                       // -1..1, screens
                    translationY = rel * size.height * 0.35f   // parallax
                    alpha = 1f - (abs(rel) * 1.8f).coerceIn(0f, 1f)
                },
        )
    }
    section { /* next screen */ }
}
```

Sections define the scroll length: N sections = N screens of scroll mapped
onto the whole frame sequence. `relative` is the section's offset from the
viewport center in screens — read it inside `graphicsLayer`/draw blocks so the
motion never causes recomposition.

## Frame sources

| Source | Use when |
|---|---|
| `FrameSource.Assets("frames")` | frames are baked into the APK — instant first launch |
| `FrameSource.Files(dir)` | frames live on disk (downloaded, generated) |
| `FrameSource.Video(uri)` | you ship one small mp4 instead of 150 images — sliced on device on first launch, cached forever after |

`FrameSource.Video` picks ~12 frames per second of footage by default
(clamped to 60–360) — dense enough that neighbouring frames are close in
time, which is what makes the scrub feel smooth. Override with `frameCount`.

## Preparing frames

Bake any video into a frame sequence with the bundled tool (needs ffmpeg):

```
python tools/video_to_frames.py flight.mp4 --out app/src/main/assets/frames
```

It picks evenly spaced frames, center-crops to portrait, scales and writes
WebP. Footage with camera motion (flyovers, dolly shots, drone footage) works
best — the scroll then feels like driving the camera.

## Tuning

```kotlin
CinematicScroll(
    source = ...,
    config = CinematicScrollConfig(
        smoothing = 0.10f,      // fraction of remaining distance per tick
        maxStepFrames = 4.5f,   // catch-up speed cap, frames per tick
        overscan = 1.12f,       // draw headroom for the parallax shift
        tiltEnabled = true,
        tiltStrength = 3.5f,    // image shift per radian of device turn
        tiltSmoothing = 0.18f,
    ),
    state = rememberCinematicScrollState(),  // progress / frameIndex / frameCount / isReady
) { ... }
```

## How it works

- **Scrub** — scroll position maps to a frame index through an exponential
  lerp with a capped catch-up speed, so flings feel like a camera dolly, not
  a slideshow.
- **Two-tier cache** — a quarter-res copy of the whole sequence is decoded
  once at startup, so fast motion always has a frame to show (motion hides
  the softness the way motion blur would); full-res frames decode in a
  sliding LRU window and take over when motion settles. Hysteresis between
  tiers prevents sharp/soft flicker.
- **Tilt parallax** — raw gyroscope integration with an exponential spring
  back to center: stable in any phone pose (no Euler-angle gimbal lock), and
  the image is drawn with overscan so shifts never reveal edges.

## Demo

The `:app` module is a complete example — its footage ships as a single mp4
(`res/raw/tunnel.mp4`, generated procedurally by `tools/generate_frames.py`)
and goes through the `FrameSource.Video` path on first launch.

## License

[MIT](LICENSE)
