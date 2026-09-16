# Chromium and JCEF compatibility

WinLandCraft uses the stock external [MCEF](https://github.com/CinemaMod/mcef/tree/1.21.4) mod
(`com.cinemamod:mcef-fabric`, pinned as `mcef_version` in `gradle.properties`) as a regular
dependency. There is no forked MCEF bridge, no custom JCEF/CEF build, no Chromium companion JAR,
and no MCEF mixins. `fabric.mod.json` declares a dependency on `mcef` and no longer breaks it.

MCEF downloads and verifies its own native browser runtime on first launch. WinLandCraft adds no
`--enable-features`/`--disable-features` flags and performs no paint-thread handoff: browser
lifecycle, OSR paint, and audio capture all run through MCEF's own integration unchanged.

## Consequences

- Video/audio codec support is whatever stock MCEF's CEF build ships (VP9/Opus software paths work;
  H.264 hardware encode is not guaranteed).
- Multiplayer app streaming no longer touches Chromium at all: `StreamEncoder`/`StreamDecoder`
  drive bundled FFmpeg (JavaCPP presets `ffmpeg_version` in `gradle.properties`, Jar-in-Jar for
  Windows/Linux/macOS x64+ARM, except Windows ARM64 which has no upstream FFmpeg build). The `-gpl`
  classifier builds are used because only they bundle libx264, the software fallback. The sender
  probes `h264_nvenc`, `h264_amf`, `h264_qsv`, `h264_videotoolbox`, then `libx264`; audio is
  `libopus`/`opus` at 48 kHz stereo. The Minecraft relay (`StreamProtocol`/`StreamRelay`/
  `StreamMedia`) is unchanged: H.264 Annex B video plus Opus audio in the same envelopes, so the
  wire format stays compatible with older v5 senders that still emit VP9 (the FFmpeg receiver
  decodes VP9 too).
- Local Video Player / Image Viewer still use Chromium's `<video>`/`<img>` elements through the
  shared MCEF runtime, so playable formats are limited to what stock CEF decodes. A native mpv-based
  player is planned to replace this.

## Upgrading MCEF

1. Bump `mcef_version` in `gradle.properties` to the matching `2.x-1.21.4` release.
2. Review upstream MCEF changes for Alloy, OSR, audio, and external-message-pump behavior.
3. Run `./dev.sh clean check build` and the native smoke-test matrix from `AGENTS.md`.

Useful log searches:

```text
Chromium Embedded Framework initialized
Stream relay
```
