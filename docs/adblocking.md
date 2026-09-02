# Adblocking architecture

WinLandCraft uses Brave's [`adblock-rust`](https://github.com/brave/adblock-rust) engine through a small JNI boundary. It is not a copied Brave DLL: Brave integrates the same Rust engine into its browser process, while WinLandCraft builds the engine as a dedicated shared library and connects its decisions to JCEF.

The Rust crate is pinned to `0.13.3` in `adblockNative/Cargo.lock`. Filter data and redirect/scriptlet resources are pinned to adblock-rust revision `2b7ebab7bd59c4d1a470adb5a6624b35a7d8236a` and verified with SHA-256 before they enter the engine. Updating the revision requires updating all three checksums in `AdBlockAssets` together.

## Request and page integration

The JCEF request handler asks the native engine about each resource on CEF's IO thread. Blocking decisions cancel the request; redirect resources and URL rewrites modify it in `onBeforeResourceLoad`, the callback where CEF permits mutation. The engine is immutable after initialization and shared for concurrent reads.

Page-specific selectors and scriptlets are queried at navigation start and again at load completion. The first successful injection installs:

- hostname-specific CSS rules;
- trusted scriptlets supplied by the pinned Brave resources;
- a bounded `MutationObserver` that reports newly observed classes and IDs in batches and adds matching generic selectors.

The observer deduplicates values, caps retained names, limits message size, and allows only one query in flight per frame. Pure-CSS procedural style rules are compiled into the same stylesheet. Procedural DOM actions that cannot be represented as CSS are counted in health logs but are not executed; adding them requires a reviewed content-script interpreter rather than evaluating filter JSON as code.

If native extraction, loading, asset verification, or engine construction fails, browsing remains available without request filtering. The failure is logged rather than introducing a second filtering engine with different behavior.

## Native packaging

`adblockNative` builds with Rust 1.97 and produces no committed binary output. A local Gradle build embeds the native library for its host OS and architecture under `META-INF/natives`. The CI matrix builds Linux, Windows, and Intel macOS libraries separately, then merges them into the distributable JAR. Do not hand a host-only local JAR to a player on another OS; use the universal CI artifact or build on that player's OS.

`verifyAdBlockNative` loads the release library in a separate JVM and exercises JNI initialization, blocking, exceptions, site cosmetics, and dynamic generic selectors. Rust unit tests cover the engine boundary without a JVM. In-game validation should include YouTube navigation and playback, a general adblock test page, a site that uses redirect resources, and browser close/reopen on Linux and Windows.

`adblock-rust` is distributed under the [Mozilla Public License 2.0](https://github.com/brave/adblock-rust/blob/master/LICENSE). WinLandCraft's JNI bridge source is in `adblockNative/src` and the exact dependency graph is recorded in `Cargo.lock`.
