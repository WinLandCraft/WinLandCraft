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
  H.264 hardware encode is not guaranteed). The previous custom `proprietary_codecs`/`ffmpeg_branding`
  runtime, the Linux VA-API shared-memory patchset, and the `AcceleratedVideoDecoder`/
  `AcceleratedVideoEncoder` flags are gone.
- Multiplayer app streaming is temporarily disabled in `StreamClient.start()` while the media
  pipeline is reimplemented on FFmpeg instead of Chromium WebCodecs. The Minecraft relay
  (`StreamProtocol`/`StreamRelay`/`StreamMedia`) is untouched and still forwards packets; no sender
  currently produces them.
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
