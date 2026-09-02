# WinLandCraft contributor guide

These instructions apply to the entire repository.

## Project shape

- WinLandCraft targets Minecraft 1.21.4, Fabric Loader, Fabric API, and Java 21.
- Put client-only code in `src/client`; keep `src/main` safe for dedicated servers.
- Dependency-free regression programs live in `src/geometryCheck` and run as part of `check`.
- `mcefFork` assembles the embedded browser compatibility JAR. It combines MCEF 2.1.6 integration classes with the pinned JCEF classes in `gradle.properties`; do not add a separate MCEF runtime dependency.
- Chromium/JCEF compatibility decisions are documented in `docs/chromium-compatibility.md`. Update that document whenever the pinned runtime, browser flags, download source, or lifecycle handling changes.
- `adblockNative` is the pinned Rust/JNI adblock engine. Its request, page-injection, asset, and packaging invariants are documented in `docs/adblocking.md`.
- Native control icons and hover-state invariants are documented in `docs/ui-icons.md`. Keep the `PixelIcon` order synchronized with its atlas and packaged license.

## Browser invariants

- JCEF lifecycle, audio, and paint callbacks are not Minecraft render-thread callbacks.
- OpenGL calls and MCEF texture creation/deletion must run on Minecraft's render thread.
- CEF-owned `ByteBuffer` data is valid only for the callback. Copy data before returning if another thread will consume it.
- Keep frame, audio, network, and render queues bounded. Prefer dropping or coalescing stale media over accumulating latency.
- Close browser views on the render thread and make cleanup idempotent. Disconnect callbacks can originate on Netty threads.
- Merge Chromium feature lists instead of replacing existing `--enable-features` or `--disable-features` arguments. Apply startup flags consistently to both `CefApp.startup` and `CefApp.getInstance`.
- MCEF/JCEF mixins use `remap = false`. Register new mixins in `winlandcraft.client.mixins.json` and extend `verifyMixinPackaging` so missing production classes fail the build.
- CEF request objects may only be mutated in callbacks that explicitly allow it. Keep the adblock engine immutable after initialization and bound every page-to-Java cosmetic query.
- Screen-light sampling reuses `PanelSurface`'s mip chain and asynchronous bounded PBO readback. Do not synchronously read a full browser texture or move pixel analysis off the render thread with a live GL buffer.
- Solas integration is an explicit, non-destructive shader-pack copy. Keep SSBO binding changes, buffer layout, patch anchors, and compatibility limits synchronized with `docs/screen-lighting.md` and its regression check.
- Screen-light curves must use `GroupCurve`'s cylindrical radius and facing; do not approximate them as a flat rectangle or an unbounded number of point lights. The configured range is a minimum and `ScreenLighting.effectiveRange` owns its bounded size scaling.
- Private panel composition may bypass Iris, but final world surfaces and UI must be submitted through `WorldRenderContext.consumers()`. The failure analysis and render-state contract are documented in `docs/panel-rendering.md`.
- OpenGL object names are recyclable. Invalidate surface sampler state on allocation and resize; never infer that an allocation survived because its numeric texture ID did not change.

## Implementation style

- Match the existing compact Java style and use Java 21 APIs. Avoid new dependencies for small utilities.
- Keep comments focused on non-obvious invariants, protocol constraints, and upstream compatibility bugs.
- Preserve user changes in a dirty worktree. Do not commit or push unless explicitly requested.
- Bump `mod_version` for testable behavior changes so logs and artifacts identify the exact build.
- Do not commit generated output, runtime natives, launcher logs, crash dumps, or local Gradle caches.
- Pin Rust crates with `Cargo.lock`; build native libraries from source and package cross-platform binaries through the CI matrix.

## Verification

Run the complete gate after code or resource changes:

```sh
./dev.sh clean check build
```

The distributable JAR is written to `build/libs/`. Before handing it off, run `git diff --check` and report the artifact name and SHA-256.

Automated checks cannot validate native CEF behavior. Browser lifecycle, OSR paint, audio capture, WebCodecs hardware selection, and cross-platform GPU flags require an in-game smoke test on every affected OS. For Chromium changes, test at least:

1. Opening, navigating, resizing, closing, and reopening Browser and a custom webapp.
2. A single-page-app navigation such as a real click on YouTube that changes the URL without a full page load.
3. Saving and quitting with several browser views open.
4. Stream encode/decode and audio on one Linux GPU stack and one Windows GPU stack.
