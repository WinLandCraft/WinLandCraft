# WinLandCraft plugin API v2: external surfaces and audio

**Since WinLandCraft 0.1.83-dev; optional GPU textures since 0.1.84-dev.** Public types live in `dev.winlandcraft.api.v2`, and the Fabric entrypoint is **`winlandcraft:plugins_v2`**. V1 remains unchanged in its original package and entrypoint. Choose v2 for capture tools, emulators, external game engines, generated images, or anything else that produces frames/audio; v2 also has native Canvas and managed Chromium/hybrid apps.

A plugin owns its content and optional external process/native engine. WinLandCraft owns panel rendering, host input capture/locking, focus, picking, placement, resizing/scaling, grouping, curves, the pill, and streaming the composed window. As an example, Windows-only WGC plugin can supply captured frames without adding Windows dependencies to the host. WGC capture itself is not included: the plugin must implement capture, OS checks, permissions/UI, native packaging, and graphics-backend interop or CPU readback.

## Packaging and registration

Use Java 21, Minecraft 1.21.4, Fabric, and WinLandCraft >=0.1.83-dev. Compile against the **compile-only** `winlandcraft-0.1.83-dev-plugin-api.jar` (contains both API versions). Never bundle the API or MCEF/JCEF. Plain Java Gradle builds work; the build/setup recipe in [PLUGINS.md](PLUGINS.md) still applies, but import v2 types and use this entrypoint:

```json
{
  "schemaVersion": 1,
  "id": "capture_demo",
  "version": "1.0.0",
  "name": "Capture Demo",
  "environment": "client",
  "entrypoints": { "winlandcraft:plugins_v2": ["example.CapturePlugin"] },
  "depends": {
    "fabricloader": ">=0.16.9",
    "java": ">=21",
    "minecraft": "1.21.4",
    "winlandcraft": ">=0.1.83-dev"
  }
}
```

```java
package example;
import dev.winlandcraft.api.v2.*;

public final class CapturePlugin implements WinLandCraftPlugin {
    public void register(PluginRegistry registry) {
        registry.register(AppDefinition.builder(
            "capture_demo:display", "External Display", AppKind.SURFACE,
            Display::new).size(1280, 720).build());
    }
}
```

App IDs must use your mod ID namespace. The factory creates a fresh instance per window session. Optional `.icon(...)`, `.extensions(...)`, and `.fileNames(...)` integrate with Apps/File Manager exactly as in v1. Initial size is required conceptually, with a 1280x720 default; `.size(...)` accepts 320 4096 wide and 180 4096 high. `AppKind` supports `NATIVE`, `CHROMIUM`, `HYBRID`, and `SURFACE`. SURFACE creates no browser. All kinds may submit frames/audio; request `browser()` only for CHROMIUM/HYBRID.

Registrations across versions share the same ID namespace and window limits. Do not register the same app ID in both entrypoints. One version per app is simplest. Apps appear automatically in the launcher/taskbar, and registered file handlers participate in the placement chooser. External native binaries must handle unsupported operating systems gracefully. Installing the plugin does not grant a separate sandbox: Java mods are trusted local code.

## Pixel frames

Get the window-owned handle during `onOpen` and submit directly from your producer thread:

```java
package example;
import dev.winlandcraft.api.v2.*;
import java.nio.ByteBuffer;

public final class Display implements App {
    private Thread producer;
    public void onOpen(WindowContext window) {
        FrameSurface output = window.frames();
        // Replace this test pattern with decoded/captured engine frames.
        producer = Thread.ofVirtual().name("Capture demo").start(() -> {
            ByteBuffer pixels = ByteBuffer.allocate(320 * 180 * 4);
            int phase = 0;
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    pixels.clear();
                    for (int y = 0; y < 180; y++) for (int x = 0; x < 320; x++)
                        pixels.put((byte)(x + phase)).put((byte)y).put((byte)80).put((byte)255);
                    pixels.flip();
                    if (!output.submit(pixels, 320, 180, 320 * 4, PixelFormat.RGBA8)) break;
                    phase++;
                    Thread.sleep(33);
                }
            } catch (InterruptedException stop) {
                Thread.currentThread().interrupt();
            }
        });
    }
    public void onClose() { if (producer != null) producer.interrupt(); }
}
```

`FrameSurface.submit(buffer,width,height,rowStride,format)`:

- Thread-safe, accepts heap or direct ByteBuffers, and copies before returning. You may reuse/release your buffer after return. Do not mutate it concurrently during the call.
- Reads from the current buffer position without changing its position/limit. Rows are top-to-bottom; stride is in bytes and must be at least `width * 4`. Padding is discarded. The last row needs only its pixel bytes.
- `RGBA8` means byte order R,G,B,A; `BGRA8` means B,G,R,A. Each channel is unsigned 8-bit. Use straight alpha; convert premultiplied sources or force alpha 255 for opaque desktop capture.
- Each dimension is 1 4096. Oversized, malformed, or undersized buffers throw IllegalArgumentException; downscale larger captures before submission. Invalid format/null buffers are programmer errors.
- Retains **one newest pending frame**. New submissions replace pending old ones. `true` means accepted, not necessarily displayed; `false` means the window/session has closed. Capture at a sensible rate rather than using this as a busy-loop signal.
- `clear()` discards pending content and hides the displayed frame on the next composition. Texture/staging capacity remains owned until window close. Closing rejects producers before releasing GPU resources.

The latest image is fitted inside the whole logical window, preserving its aspect ratio with letterboxing. It is drawn above the normal background/browser and below your native `render(Canvas)` UI. A surface can remain frozen while native controls update. No new frame means no pixel upload. The normal composed surface receives smoothing, curvature, and stream capture.

The `submit` path is a **CPU pixel API**. GPU producers can instead use the optional GPU source below. The plugin owns capture, JNI or IPC, decoding, and color conversion. At maximum resolution, pending frames, upload staging, and GPU textures consume substantial memory; keep resolution and producer cadence appropriate. The host does not expose its own render targets to plugins.

## Resize, scale, and input

`onResize(width,height)` receives the logical content resolution when ordinary resizing changes it. Tell your external renderer about this on its own thread, then submit its resulting frames. Until the new frame arrives, the existing one fits the new window. Ctrl-scaling keeps logical dimensions/aspect unchanged and does not ask the engine to resize.

Native pointer/key callbacks have the same contract as v1, but use v2 App/Canvas/WindowContext types. Input is local logical-window pixels, not automatically converted to source-frame pixels. To map a fitted frame, use `scale = min(windowWidth/frameWidth, windowHeight/frameHeight)` and subtract the centered letterbox offset before dividing by scale; reject clicks in the bars. The plugin knows the dimensions it submitted. For input-driven engines, forward these local events to your engine and request typing via `window.requestKeyboard(true)` on an appropriate click. Do not intercept Minecraft's raw input or lock its cursor yourself.

**Capture-only apps can ignore input callbacks and never request typing.** WinLandCraft does not control the captured OS application. It only provides the normal panel controls. The user can still move, resize, group, and close the panel.

## Audio

Get a window-owned AudioOutput on the client thread:

```java
AudioOutput sound = window.audio();
// Capture sources already audible on the desktop usually should not echo locally:
sound.localPlayback(false);
// In your audio callback/worker: L0,R0,L1,R1,..., at exactly 48,000 Hz.
boolean accepted = sound.submit(interleavedFloatPcm);
```

- Input is **48 kHz, interleaved stereo float PCM**, 1 4,800 frames per call (2 9,600 floats). Resample/downmix other formats in the plugin. Small regular blocks around 10 20 ms are recommended.
- The host copies, converts to planar stereo for its encoder, clamps samples to [-1,1], and replaces non-finite samples with silence. Never mutate an array concurrently with submission.
- Submission, `localPlayback`, and `clear` are thread-safe. A paced producer is required; submitting more than about 200 ms ahead returns false. Closed sessions also return false. Drop stale audio rather than spinning/retrying it forever.
- Local playback defaults to on, using the host's bounded Java Sound monitor. `localPlayback(false)` affects local monitoring only; window streaming still receives the audio when an encoder is active.
- `clear()` drops queued local audio and resets producer timing. It cannot retract audio already handed to the encoder/network/output device. Closing releases queues and stops monitoring.
- Requesting custom audio selects it instead of Chromium audio for **that window's stream**. It does not mute Chromium's local playback. V2 does not automatically mix arbitrary plugin and browser audio; choose the source deliberately.
- The host timestamps accepted audio against the active stream encoder clock. Frames are latest-state video, not a timestamped offline media timeline. For a playback engine, maintain your own real-time source pacing and avoid buffering long backlogs.

This supplies playback/stream input, not microphone capture or OS loopback acquisition. Plugins implement acquisition. Codecs and relay limitations remain the host's normal streaming limitations; this API does not add codecs.

## Common UI, lifecycle, and version rules

V2 duplicates the stable application contract in its own package: `App`, `AppDefinition`, `Canvas`, `WindowContext`, and `BrowserView`. Native drawing, browser rectangles, file handlers, title updates, clipboard/data paths, session-bound worker completions, and host-managed focus behave as described in [the common v1 guide](PLUGINS.md). Use v2 imports throughout your v2 app; there is no requirement to reference v1 types. `window.frames()` and `window.audio()` are v2-only additions. Acquire handles on the client thread, then their submission methods are safe from workers; ordinary Context/Canvas operations remain client-thread-only.

Stop external processes, capture sessions, and producer threads in `onClose`. Late submissions are rejected. A new window session needs new handles. A plugin callback failure closes managed media and leaves an error panel. Native process crashes or native code loaded into the Minecraft JVM cannot be sandboxed by this API. Use isolated IPC where appropriate, and do not block callbacks on I/O or process startup.

V1 sources, signatures, and entrypoint remain unchanged. V2 has its own frozen baseline, `docs/plugin-api-v2.txt`; future extensions must preserve old signatures and add only optional/default methods. The build checks both versions and compiles an API-only SURFACE fixture. CPU tests cover row stride, copying, bounded latest-frame behavior, invalid inputs, stale handles, audio conversion, and registration. Actual GL upload, audio-device playback, streaming A/V, and native capture need in-game smoke tests on the affected operating systems.


## Optional GPU surfaces (since 0.1.84-dev, still API v2)

Existing CPU plugins need no changes. To use `GpuSource`/`GpuFrame`, compile against
`winlandcraft-0.1.84-dev-plugin-api.jar` or newer and set your Fabric dependency to
`"winlandcraft": ">=0.1.84-dev"`. Do not package host API classes in your plugin.

`window.frames().supportsGpu()` checks OpenGL 4.3 or ARB_copy_image on the current
render context. If false, use the existing CPU `submit` path. This capability does
not promise that your engine's D3D/Vulkan/other-context interop is supported.
The host's GPU path is platform-neutral; test each supported driver/backend.

Attach a `GpuSource` on the render thread, for example in `onOpen` or via
`window.execute`. The host polls `acquire()` during panel composition. Return null
when no fresh frame is ready; the last copied image stays visible. Example:

```java
FrameSurface surface = window.frames();
if (surface.supportsGpu() && engine.canExportOpenGlTexture()) {
    surface.gpuSource(new GpuSource() {
        public GpuFrame acquire() {
            // Your engine integration: nonblocking, synchronized, current GL context.
            // Return null if the producer is still writing or has no new frame.
            var frame = engine.tryAcquireTexture();
            if (frame == null) return null;
            return new GpuFrame(frame.textureId(), frame.width(), frame.height(), false);
        }
        public void release(GpuFrame frame) {
            engine.releaseTextureAfterGlCopy();
        }
        public void close() {
            engine.closeGpuExport();
        }
    });
} else {
    // Continue producing owned CPU pixels using surface.submit(...).
}
```

`engine` above represents your plugin's integration, not a host-provided class.
A same-context OpenGL renderer can create/render its texture in `acquire()` and
return it directly. An FBO-rendered image typically uses `topLeftOrigin=false`;
set true if texture v=0 represents the top row. The host letterboxes the result
just like CPU frames; existing input coordinate mapping and resize callbacks apply.

Texture requirements:

- A live single-sample `GL_TEXTURE_2D`, visible in Minecraft's current GL context,
  sized 1..4096 per axis with sized internal format `GL_RGBA8` and straight alpha.
- Level 0 must match the reported dimensions, base level must be zero, and the
  minification filter must be `GL_LINEAR` or `GL_NEAREST` (no required mip chain).
- Keep the texture alive and stable from acquire through release. All callbacks
  run on the render thread. Restore every GL state you change, including bindings,
  framebuffers, viewport, pixel-store state, and any engine-specific GL state cache.
- Never wait indefinitely for a producer, and never attach/detach sources or close
  the window inside source callbacks. Keep producer queues bounded.

The host enqueues a GPU-to-GPU copy into its own texture before `release(frame)`.
No frame pixels pass through Java arrays, upload buffers, or CPU readback on this
path. A GPU copy and normal panel composition still occur. This ownership boundary
is necessary because Minecraft batches drawing after the producer callback returns.
The host never deletes the producer's texture.

For every non-null acquired frame, release runs even if validation/copy throws.
Acquire must undo its own locks if it throws before returning a frame. Release
means the copy command was issued, **not that the GPU has finished**. Same-context
commands are ordered; shared-context or cross-API producers must implement fences
and the appropriate interop unlock/release before reusing the texture. Avoid
`glFinish` per frame; use nonblocking producer handoff and GPU synchronization.
For WGC specifically, the plugin must bridge D3D textures into a compatible GL
texture and acquire/release that interop resource. Passing a D3D pointer as a GL
texture ID will not work. CPU fallback remains necessary when interop is absent.

A successful attachment transfers source lifecycle ownership to the host. It
calls `close()` once on replacement, detach, failure, or window close, before the
app's normal `onClose`. Reattaching the same currently attached object is a no-op;
never reuse an already closed source. A rejected attachment leaves ownership with
the plugin. `gpuSource(null)` detaches immediately and enables CPU submissions;
`clear()` can be called from any thread and detaches/hides on the next composition.
CPU submissions return false while a GPU source is attached. GPU callback failures
become the normal plugin error panel; cleanup exceptions are logged and contained.
GPU support changes no input ownership, audio API, or window controls. Streaming
still uses the existing composed-window streaming pipeline and its own transfers.

Validation: automated tests cover old v2 signatures/default compatibility,
API-only GPU producer compilation, and acquire/release on success, no-frame, and
failure. In-game checks must cover orientation, color/alpha, resize, source switch,
close/reopen, shader composition, and your native synchronization on each target OS.
