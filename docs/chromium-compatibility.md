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

- Enables `VaapiVideoDecoder` and `VaapiVideoEncoder`.
- Overrides Chromium's GPU blocklist.
- Selects ANGLE backed by desktop OpenGL unless another GL backend is already configured.
- Disables Vulkan when running under Wayland, avoiding Chromium's unsupported Vulkan path in the current runtime.

These are preferences, not guarantees. WebCodecs still probes each H.264/VP9 encoder and decoder configuration and can fall back to software. Log fields report Chromium's requested acceleration preference, not proof that a vendor engine accepted every frame.

## Runtime downloads and upgrades

`McefRuntimeMixin` redirects native downloads to the release matching `jcef_commit` and verifies the published SHA-256 archive checksum. Never update only `chromium_version`, only the Java classes, or only the native archive.

For an upgrade:

1. Select a JCEF Java JAR and native release built from the same commit.
2. Update `jcef_commit` and `chromium_version` together.
3. Review upstream CEF changes between the old and new branches for Alloy, OSR, audio, and external-message-pump behavior.
4. Build with `./dev.sh clean check build` and verify the embedded compatibility JAR is present.
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

For a native crash, preserve `hs_err_pid*.log`. The native stack and fault address are more useful than the final lines of `latest.log`.
