# WinLandCraft plugin API v2: external surfaces and audio

**Since WinLandCraft 0.1.83-dev.** Public types live in `dev.winlandcraft.api.v2`, and the Fabric entrypoint is **`winlandcraft:plugins_v2`**. V1 remains unchanged in its original package and entrypoint. Choose v2 for capture tools, emulators, external game engines, generated images, or anything else that produces frames/audio; v2 also has native Canvas and managed Chromium/hybrid apps.

A plugin owns its content and optional external process/native engine. WinLandCraft owns panel rendering, host input capture/locking, focus, picking, placement, resizing/scaling, grouping, curves, the pill, and streaming the composed window. A Windows-only WGC plugin can supply captured frames without adding Windows dependencies to the host. WGC capture itself is not included: the plugin must implement capture, OS checks, permissions/UI, native packaging, and any GPU readback.

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

App IDs must use your mod ID namespace. The factory creates a fresh instance per window session. Optional `.icon(...)`, `.extensions(...)`, and `.fileNames(...)` integrate with Apps/File Manager exactly as in v1. Initial size is required conceptually, with a 1280x720 default; `.size(...)` accepts 320–4096 wide and 180–4096 high. `AppKind` supports `NATIVE`, `CHROMIUM`, `HYBRID`, and `SURFACE`. SURFACE creates no browser. All kinds may submit frames/audio; request `browser()` only for CHROMIUM/HYBRID.

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
- Each dimension is 1–4096. Oversized, malformed, or undersized buffers throw IllegalArgumentException; downscale larger captures before submission. Invalid format/null buffers are programmer errors.
- Retains **one newest pending frame**. New submissions replace pending old ones. `true` means accepted, not necessarily displayed; `false` means the window/session has closed. Capture at a sensible rate rather than using this as a busy-loop signal.
- `clear()` discards pending content and hides the displayed frame on the next composition. Texture/staging capacity remains owned until window close. Closing rejects producers before releasing GPU resources.

The latest image is fitted inside the whole logical window, preserving its aspect ratio with letterboxing. It is drawn above the normal background/browser and below your native `render(Canvas)` UI. A surface can remain frozen while native controls update. No new frame means no pixel upload. The normal composed surface receives smoothing, curvature, and stream capture.

This is a **CPU pixel API**, not shared GPU handles, zero-copy D3D textures, or a game-engine integration SDK. The plugin performs WGC/D3D readback, JNI or IPC, decoding, and any color conversion. At maximum resolution, pending frames, upload staging, and GPU textures consume substantial memory; keep resolution and producer cadence appropriate. There is no unlimited native buffer/GL access exposed through the API.

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

- Input is **48 kHz, interleaved stereo float PCM**, 1–4,800 frames per call (2–9,600 floats). Resample/downmix other formats in the plugin. Small regular blocks around 10–20 ms are recommended.
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
