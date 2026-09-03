# Chromium and JCEF compatibility

WinLandCraft embeds a compatibility runtime rather than loading the stock MCEF browser unchanged. The `mcefFork` subproject keeps MCEF 2.1.6's Minecraft integration and renderer while replacing its `org.cef` classes and native download with an ABI-matched JCEF/CEF build. The exact JCEF commit and Chromium version are pinned in `gradle.properties`.

This arrangement preserves the existing Minecraft-facing API, but Chromium upgrades can change native lifecycle and threading behavior. Treat a JCEF Java JAR and its native archive as an inseparable pair.

## Off-screen paint callback threading

The legacy MCEF `MCEFBrowser.onPaint` implementation performs OpenGL texture uploads directly in the callback. With the Chromium 151 JCEF external message pump, that callback runs on `JCEF-Lifecycle`, not Minecraft's render thread. Calling `RenderSystem.bindTexture` there throws `RenderSystem called from wrong thread`; the Java shell remains visible while the browser texture stays black.

`McefPaintMixin` owns the compatibility handoff:

- It copies CEF's BGRA buffer before the callback returns.
- It copies dirty rectangles because CEF owns those callback objects too.
- It keeps at most three pending frames, coalescing superseded dirty regions into the newest complete buffer.
- It pools a small number of direct buffers to avoid per-frame native allocation churn.
- It invokes the original MCEF upload only from Minecraft's render thread.
- Browser close rejects and releases pending work.

Do not replace this with `Minecraft.execute(() -> onPaint(...))` using the original buffer. CEF does not promise that buffer remains valid after `onPaint` returns, and queuing every frame without a bound can consume hundreds of megabytes during a render stall.

The rendering itself is unchanged. `MCEFRenderer.class` in the compatibility JAR is byte-for-byte identical to the MCEF 2.1.6 renderer. The handoff copies the original BGRA pixels without encoding, resizing, or resampling. A newer Chromium version can still produce subtly different font antialiasing or compositing, but the Minecraft texture resolution and `GL_LINEAR` filtering path are the same.

## Chromium 151 Alloy soft-navigation crash

Chromium 151 enables `ImmersiveReadAnything` by default. Its page-load metrics setup registers `ReadAnythingSoftNavigationObserver` for Alloy-style CEF `WebContents`. On a soft navigation—commonly a real click followed by `history.pushState()` and a repaint—the observer calls:

```text
tabs::TabInterface::GetFromContents(content::WebContents*)
```

That accessor is only valid when the `WebContents` is known to be a Chrome tab. Alloy CEF browsers are not Chrome tabs, so the lookup dereferences null before the observer's own null check and crashes native code at address `0x10`. A typical stack begins:

```text
tabs::TabInterface::GetFromContents
ReadAnythingSoftNavigationObserver::OnSoftNavigation
page_load_metrics::PageLoadTracker::OnSoftNavigation
CefDoMessageLoopWork
```

This is [CEF issue #4234](https://github.com/chromiumembedded/cef/issues/4234). Chromium's API documentation explicitly says [`GetFromContents` crashes for non-tab contents](https://chromium.googlesource.com/chromium/src/+/refs/tags/137.0.7113.1/components/tabs/public/tab_interface.h) and provides `MaybeGetFromContents` for this case. The native source fix changes that one call and is tracked for CEF/Chromium 152.

Until the bundled runtime contains that fix, `McefChromiumFlagsMixin` adds these verified workarounds on every OS:

```text
--disable-features=ImmersiveReadAnything,SoftNavigationDetection
```

`ImmersiveReadAnything` is Chrome browser UI that MCEF cannot expose. `SoftNavigationDetection` is Chromium page-metrics instrumentation; disabling it does not disable History API navigation, client-side routers, links, or normal page loading. Both flags are intentionally applied to `CefApp.startup` and `CefApp.getInstance`.

Remove these flags only after upgrading to an ABI-matched CEF build containing the null-safe fix and reproducing the issue's real-user-interaction test without a crash. A scripted `element.click()` alone is not a sufficient regression test because Chromium may not classify it as a soft navigation.

## Linux GPU flags

On Linux, `McefChromiumFlagsMixin` additionally:

- Enables Chromium 151's `AcceleratedVideoDecoder` and disabled-by-default
  `AcceleratedVideoEncoder` features. The older `VaapiVideoDecoder` and
  `VaapiVideoEncoder` strings are not feature names in this runtime and are
  silently ignored by Chromium.
- Overrides Chromium's GPU blocklist.
- Selects ANGLE backed by desktop OpenGL unless another GL backend is already configured.
- Disables Vulkan when running under Wayland, avoiding Chromium's unsupported Vulkan path in the current runtime.

These are preferences, not guarantees. WebCodecs still probes each H.264/VP9 encoder and decoder configuration and can fall back to software. Log fields report Chromium's requested acceleration preference, not proof that a vendor engine accepted every frame.

### Linux VA-API input staging

Chromium 151 initializes `VideoEncodeAcceleratorAdapter` before WebCodecs sends
its first frame and unconditionally selects GPU-memory-buffer input on Linux.
WinLandCraft's codec page creates CPU-owned RGBA `VideoFrame`s from the bounded
loopback capture. Under CEF's off-screen ANGLE/Wayland configuration, Chromium
cannot create the requested `VEA_READ_CAMERA_AND_CPU_READ_WRITE` mappable shared
image. `chrome_debug.log` then reports `Unable to create a mappable shared
image` and `Allocating a buffer failed`; WebCodecs closes the otherwise valid
H.264 hardware encoder before its first output.

The Linux runtime carries the reproducible
`linux-vaapi-shmem-v1` patch in `tools/cef/patches`. It makes the adapter convert
the CPU RGBA frame to NV12 in pooled shared memory. Chromium's
`VaapiVideoEncodeAccelerator` then uploads that NV12 frame to a VA surface and
still performs H.264 encoding in hardware. This does not enable software H.264
or misreport software encoding as hardware; it replaces only the unavailable
GPU-buffer staging allocation. Explicit GPU-buffer preferences remain intact.
Every Linux runtime archive records the patchset in
`WINLANDCRAFT-CODEC-BUILD.properties`, and packaging rejects an unpatched Linux
archive.

WebCodecs reports fatal encoder errors asynchronously and closes the codec
before calling its error callback. The stream worker records that first native
failure and checks the codec state immediately before `encode()`. Auto mode
blocks the failed candidate and probes the next one; an explicit codec choice
stops with the original failure. Never replace that diagnostic with the later,
generic `encode on a closed codec` exception.

## Opaque origin on codec status POSTs

Chromium 151 serializes the `Origin` header of `fetch` POSTs from MCEF's off-screen codec page as the literal value `null` on both Linux and Windows, even though the displayed page and request URLs use the same loopback origin. GET requests from the same page do not carry that header. Rejecting the literal value first prevents worker heartbeats from making the Java endpoint ready; if only heartbeats are exempted, encoded `/packet` uploads are still rejected with HTTP 403 while `videoOut` rises and every frame is dropped.

`MediaBridge` permits this Chromium-specific value only for its two POST routes, `/status` and `/packet`, after checking the loopback peer, exact ephemeral `Host`, and the endpoint's random 256-bit path token. A missing origin remains valid for the bridge's GETs, the exact origin remains valid, and opaque origins on every other route plus all foreign origins remain rejected. Do not broadly allow `Origin: null`: sandboxed documents and local files can also produce it.

## Runtime downloads and upgrades

Release builds produce a small main mod and a client-only `winlandcraft-chromium-<runtime-version>.jar`. The companion is a resource-only Fabric mod and carries the native archives under `META-INF/winlandcraft/cef`; placing both JARs in `mods/` makes those resources visible through Fabric's shared class loader. Dedicated servers install only the main JAR and never load or extract Chromium. `chromium_version` pins the upstream browser while `chromium_runtime_version` identifies WinLandCraft's native patch revision, so a new main mod rejects an older companion containing the same upstream Chromium binary. The archives are built from CEF commit `89cd5813e47d84c68e56ced336c2c01b7dc77b8d` with the Chromium version and JCEF Java commit pinned in `gradle.properties`. The codec-critical GN arguments are:

```text
proprietary_codecs=true
ffmpeg_branding=Chrome
```

Those arguments select Chromium's Chrome FFmpeg configuration (including H.264 and AAC decode, MP4 demuxing, and the ordinary MP3/Opus/Vorbis/FLAC paths) and compile OpenH264 software encoding. Chromium's normal platform paths remain enabled for hardware H.264 and HEVC where the OS and driver expose them. A runtime cannot claim support merely because WebCodecs accepts an encoder configuration: sender and receiver capability must both be tested.

`WinLandCraftClient` requires the companion on normal client launches, checks that its version matches the nested MCEF bridge, and confirms that it contains the current platform archive and checksum. `McefRuntimeMixin` then copies that archive out of the companion instead of contacting a release server. It verifies the archive before extraction, verifies the extracted `libcef` against `WINLANDCRAFT-CODEC-BUILD.properties`, and writes an installation marker only after both checks succeed. A stock or partially extracted runtime has no marker and is replaced on the next launch. Development environments without the companion retain the checksum-verified JCEF/Rinku download fallback.

The native bundle directory is supplied at package time with `-PcefRuntimeBundleDir=<directory>`. Each `<platform>.tar.gz` must have a matching `.sha256` sidecar and embedded codec-build manifest. `stageCefRuntimes` rejects a mismatched CEF/JCEF/Chromium revision, missing `libcef`, invalid checksum, or builds that do not record both codec arguments. `cefRuntimeJar` stores the already-compressed archives without recompression. `verifyMixinPackaging` rejects native archives in the main mod and separately validates the companion metadata and every supplied archive/sidecar pair. Never update only `chromium_version`, only the Java classes, or only the native archive.

For an upgrade:

1. Select a JCEF Java JAR and CEF native build from the same API and source revision.
2. Update `cef_commit`, `jcef_commit`, and `chromium_version` together for an
   upstream upgrade; bump `chromium_runtime_version` whenever any native patch
   or archive changes.
3. Review upstream CEF changes between the old and new branches for Alloy, OSR, audio, and external-message-pump behavior.
4. Build with `./dev.sh clean check build -PcefRuntimeBundleDir=<directory>` and verify both the main mod and Chromium companion JARs are present.
5. Test with a fresh native runtime directory or confirm the downloader selected the new commit.
6. Run the native smoke-test matrix from `AGENTS.md` on Linux and Windows before removing compatibility flags.

Useful log searches:

```text
Chromium Embedded Framework initialized
Applied Chromium CEF compatibility flags
Bridging Chromium paint callbacks
Stream codec health
Stream audio capture health
```

Codec health includes loopback request counts in `index/script/config/status` order. A healthy endpoint reaches all four, posts a `boot` status before probing codecs, and then advances to `encoding` or `decoding`. `index>0` with `script=0` indicates interception or script-load failure. `script>0` with `config>0` and `status=0` means JavaScript started but status requests were blocked or rejected; inspect the bridge security boundary before blaming WebCodecs. The startup watchdog intentionally follows status heartbeats rather than generic HTTP traffic so repeated media polling cannot disguise a dead control path.

For a native crash, preserve `hs_err_pid*.log`. The native stack and fault address are more useful than the final lines of `latest.log`.

## App streaming and audio lifecycle

BrowserPanel registers every app browser tab with StreamAudio at creation and detaches it on render-thread close. This lets an already playing private tab become a stream source without reloading its website: CEF negotiates audio capture when playback starts, not when the player later chooses Stream. Captured tabs each retain bounded local Java Sound playback, even when not published or when broadcast audio is disabled. Only the selected tab of the current publishing BrowserPanel sees an encoder endpoint. Native apps use the same off-screen drawSurface capture but have no audio source. Codec/helper views are never registered as app audio sources.

Stopping or switching publication clears the encoder pointer and releases remote browser inputs without closing the app or its local playback. Detaching a tab marks its monitor closed before removing the callback lookup, so an in-flight callback cannot recreate a monitor after teardown. No native pointers escape the audio callback.

In-game checks required on Windows and Linux: start Stream on an already playing private browser and on a standalone webapp; verify creator and viewer audio, private/background-tab isolation, tab switching, audio toggle, stop/restart, source switching to a native app, and saving/quitting with multiple tabs open. Automated geometry/packaging checks do not exercise native audio or GPU capture.

## Plugin-managed views

API v1 Chromium/hybrid apps share BrowserPanel's MCEF runtime, texture registration, BrowserEvents dispatch, resize tick, audio capture, and render-thread cleanup. The internal PluginPanel adapter hosts one standalone view with a configurable pixel rectangle; native plugin apps never create a view. `closeViews` can dispose a failed plugin's browser without deleting its visible error panel. Plugin callbacks receive no JCEF handles or native buffers. Main-frame load callbacks are marshalled through BrowserEvents before reaching plugins. Plugin views have no host-native browser context menu (page DOM context menus still work), and plugin stream replicas support owner-permitted input since 0.1.89-dev. Final native-plus-browser composition continues through PanelSurface and Fabric world consumers. No codec or runtime flags change.

API v2 SURFACE apps do not create CEF views. Submitted 48 kHz stereo PCM uses the existing bounded local-monitor and encoder packet paths with copied, reference-counted data. Requesting a plugin's custom AudioOutput selects that source for its stream instead of browser audio; Chromium local playback is unaffected. No codec/runtime flags change. Native audio-device output and GL-uploaded surfaces still require platform smoke tests.


## Local Video Player (0.1.85-dev)

VideoPlayerPanel is a standalone BrowserPanel using the shared CEF runtime, normal
StreamAudio capture/local playback and render-thread view cleanup. No codec flags
change. An HTML video element fills the panel with native controls and object-fit
contain. Playback errors and autoplay rejection are displayed in the page.

Each open player owns a loopback-only VideoFileServer with an ephemeral port,
random capability path and per-selection revision. It exposes only index.html and
the selected media; there is no user-controlled filesystem route. GET/HEAD only,
exact Host checks, foreign-Origin rejection (CEF null is accepted only behind the
capability), no CORS headers, no-store and no-referrer prevent accidental reuse.
A restrictive page CSP permits only local media and the embedded UI script/style.
Old selection URLs expire immediately. Two workers and eight queued requests per
player bound file serving; 64 KiB chunks and HTTP byte ranges avoid whole-file
buffering and support seeking. Closing stops the server and workers after closing
CEF on the render thread. Video replicas support the owner permission toggle since 0.1.89-dev.

Smoke-test on each OS: empty launch; File Manager placement and drag/drop; H.264/AAC
MP4 and VP9/Opus WebM playback/audio; seek, pause, resize and replace; unreadable or
unsupported file; close/reopen and quit with multiple playing videos; streamed A/V.
Automated HTTP tests cover ranges and file isolation but cannot validate decoding.


## Local Image Viewer (0.1.87-dev)

ImageViewerPanel uses the same private selected-file server as Video Player in
image mode, with an HTML img element and matching image MIME types. The shared
page CSP now allows same-origin images. File bytes are never inserted as HTML;
SVG is loaded in image context, not as a top-level active document. The image
viewer uses the same CEF creation, input, render-thread cleanup and endpoint
lifetime as Video Player. No runtime flags or codecs change.

HTTP regression checks cover the image page, PNG bytes/MIME, selection failure,
and associations (including SVG preferred over Notepad). Smoke-test PNG/JPEG,
transparency, animated GIF/WebP, SVG, Fit/100%/zoom/scroll, resize, replacement,
corrupt image handling and close/reopen in-game on supported OSes.


## Sender codec override (0.1.88-dev)

The owner pill persists streamCodecMode: 0 Auto, 1 H.264/prefer-hardware,
2 VP9/prefer-hardware, 3 VP9/prefer-software. Auto retains its previous candidate
order; an explicit mode filters out all other candidates and reports an error
rather than silently selecting another codec. The local encoder endpoint snapshots
this preference in config. Decoder endpoints retain mode 0 and their existing
incoming-packet codec and hardware/software probing. No viewer UI or relay payload
changes are made.

Changing the owner's preference rotates the broadcast session. Existing Stop/State
handling tears down the old encoder/decoder queues and restarts with a fresh
keyframe/timestamp epoch, keeping the source app open and in place. Encoder failure
keeps the session/pill alive with a deduplicated error so another mode can be chosen.
Hardware/software preferences remain hints in WebCodecs, not proof of acceleration.
Test Auto and each explicit candidate, failed-encoder recovery, active codec
switching with viewers, audio synchronization after reconnect, and stop/quit on
Windows and Linux. HTTP regression checks verify sender configuration and unchanged
decoder policy; pill geometry checks cover the extra row on all four edges.

Run the optional worker-selection regression with: node src/geometryCheck/codec-selection-check.cjs. It mocks capability probes against the production selection functions; native codec support still needs in-game testing.


## Remote control for all apps (0.1.89-dev)

The existing permission/relay validation now gates WorldPanel input rather than
only BrowserPanel. Browser windows retain per-controller CEF input ownership.
PluginPanel explicitly dispatches through its public app input methods so native
widgets and hybrid browser bounds are respected. RemoteAppInput balances native/
plugin keys, buttons and focus on controller handoff, revoke, cancel and stop.
Remote plugin keyboard requests cannot capture the owner's Minecraft keyboard.
No public plugin ABI or wire format changes; existing safe-key restrictions apply.
Test native text editing, file-manager navigation, image zoom, video controls,
hybrid/native plugins, permission revocation while dragging/typing, owner takeover,
viewer disconnect and world exit with two clients. HTTP/native-free tests cannot
verify CEF focus and event forwarding.
