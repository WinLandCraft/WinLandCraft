# Chromium and JCEF compatibility

WinLandCraft uses a WinLandCraft-organization fork of [MCEF](https://github.com/CinemaMod/mcef)
(`com.cinemamod:mcef-fabric`, pinned as `mcef_version` in `gradle.properties`, currently
`2.1.7-26.3`) as a regular dependency. The fork exists because upstream MCEF stopped at
1.21.4 while 26.x removed GLFW, replaced the texture/render APIs with RenderPearl, and
ships unobfuscated. There is no custom JCEF/CEF build and no Chromium companion JAR:
the fork compiles CinemaMod's audio-capable JCEF sources from its pinned submodule and
downloads the same prebuilt CEF natives at first launch. `fabric.mod.json` declares a
dependency on `mcef` and no longer breaks it.

## What the fork changes over upstream 2.1.6

- Input translation is SDL (`SDLScancode`/`SDLKeycode`/`SDLMouse`). The public
  `sendKeyPress`/`sendMousePress`/etc. signatures are unchanged, but button and
  key/modifier codes now follow SDL numbering, not GLFW.
- The renderer owns a RenderPearl `GpuTexture` instead of a raw GL texture id.
  `MCEFRenderer.getTextureID()` is gone; consume `getTexture()`/`getTextureView()`.
  Frames are swizzled BGRA to RGBA on the paint thread and uploaded on the render
  thread; popup compositing is preserved.
- Window size/cursor handling uses the SDL `Window` API (`queryFramebufferSize`,
  `selectCursor`); GLFW cursor creation is gone.
- The mod targets Fabric Loom's no-remap plugin: 26.x ships unobfuscated, so there
  are no mappings and no refmap.

MCEF downloads and verifies its own native browser runtime on first launch. WinLandCraft adds no
`--enable-features`/`--disable-features` flags and performs no paint-thread handoff: browser
lifecycle, OSR paint, and audio capture all run through MCEF's own integration unchanged.

## Consequences

- Video/audio codec support is whatever the fork's CEF build ships (VP9/Opus software paths work;
  H.264 hardware encode is not guaranteed). The CEF/Chromium build is the January-2025
  upstream one: staying on the fork means staying on that browser until the fork
  re-points its JCEF natives at a newer build.
- Browser audio capture (`CefAudioHandler`) is preserved exactly because the fork keeps
  CinemaMod's JCEF sources and natives. Stock-JCEF alternatives such as MCEF Modern do
  not capture audio and cannot feed the multiplayer stream.
- Multiplayer app streaming no longer touches Chromium at all: `StreamEncoder`/`StreamDecoder`
  drive bundled FFmpeg (JavaCPP presets `ffmpeg_version` in `gradle.properties`, Jar-in-Jar for
  Windows/Linux/macOS x64+ARM, except Windows ARM64 which has no upstream FFmpeg build). The `-gpl`
  classifier builds are used because only they bundle libx264, the software fallback. The sender
  probes `h264_nvenc`, `h264_amf`, `h264_qsv`, `h264_videotoolbox`, then `libx264`; audio is
  `libopus`/`opus` at 48 kHz stereo. The Minecraft relay (`StreamProtocol`/`StreamRelay`/
  `StreamMedia`) is unchanged: H.264 Annex B video plus Opus audio in the same envelopes, so the
  wire format stays compatible with older v5 senders that still emit VP9 (the FFmpeg receiver
  decodes VP9 too).
- There is no built-in video player; video playback lives in plugin mods through the
  v2 plugin API. Only the Image Viewer still uses a loopback file server through
  the shared MCEF runtime.

## Upgrading MCEF

1. Bump `mcef_version` in `gradle.properties` to the matching fork release.
2. Review fork changes for Alloy, OSR, audio, and external-message-pump behavior.
3. Run `./dev.sh clean check build` and the native smoke-test matrix from `AGENTS.md`.

Useful log searches:

```text
Chromium Embedded Framework initialized
Stream relay
```
