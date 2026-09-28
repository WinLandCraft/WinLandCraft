package dev.winlandcraft;

import com.cinemamod.mcef.MCEFBrowser;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.textures.GpuTexture;
import java.nio.ByteBuffer;
import java.util.concurrent.ArrayBlockingQueue;

/**
 * Captures the streaming source's browser texture for the encoder. The old
 * private-FBO re-render is gone with immediate mode; instead the newest
 * browser frame is copied GPU-to-CPU asynchronously (at most one in flight)
 * and scaled on the CPU. Native-only panels have no GPU source and stream
 * as blank: capture returns null for them.
 */
final class StreamCapture implements AutoCloseable {
    /** A pooled top-down RGBA frame. Ownership passes to the codec bridge. */
    static final class Pixels implements AutoCloseable {
        private StreamCapture owner;
        private final byte[] rgba;
        private final int width, height;
        private final long timeUs;

        private Pixels(StreamCapture owner, byte[] rgba, int width, int height, long timeUs) {
            this.owner = owner;
            this.rgba = rgba;
            this.width = width;
            this.height = height;
            this.timeUs = timeUs;
        }

        byte[] rgba() { return rgba; }
        int width() { return width; }
        int height() { return height; }
        long timeUs() { return timeUs; }

        @Override public void close() {
            var recycler = owner;
            owner = null;
            if (recycler != null) recycler.recycle(rgba);
        }
    }

    private final ArrayBlockingQueue<byte[]> freeFrames = new ArrayBlockingQueue<>(4);
    private GpuBuffer readback;
    private int readbackBytes;
    private volatile boolean inFlight;
    private boolean completedReady;
    private int completedWidth, completedHeight;
    private long completedTimeUs;
    private long captures, nextDiagnostic;

    Pixels capture(WorldPanel panel) {
        return capture(panel, StreamQuality.current(), 0);
    }

    Pixels capture(WorldPanel panel, StreamQuality quality) {
        return capture(panel, quality, 0);
    }

    Pixels capture(WorldPanel panel, StreamQuality quality, long timeUs) {
        RenderSystem.assertOnRenderThread();
        MCEFBrowser browser = panel instanceof BrowserPanel browserPanel ? browserPanel.managedBrowser() : null;
        GpuTexture texture = browser == null ? null : browser.getRenderer().getTexture();
        int logicalWidth = panel.pixelWidth(), logicalHeight = panel.pixelHeight() + panel.titlebarHeight();
        int maxHeight = quality.height(), maxWidth = maxHeight * 16 / 9;
        double factor = Math.min(1, Math.min(maxWidth / (double) logicalWidth, maxHeight / (double) logicalHeight));
        int w = Math.max(2, (int) (logicalWidth * factor) & ~1), h = Math.max(2, (int) (logicalHeight * factor) & ~1);
        // Reap the previous async copy first so a slow GPU never queues frames.
        // Snapshot dimensions with the reap: the completion callback can fire on another thread.
        final byte[] frame;
        final int sw, sh;
        synchronized (this) {
            frame = reap();
            sw = completedWidth;
            sh = completedHeight;
        }
        if (texture != null && !inFlight) {
            int tw = texture.getWidth(0), th = texture.getHeight(0);
            int bytes = tw * th * 4;
            if (tw > 0 && th > 0 && bytes > 0) {
                if (readback == null || readback.isClosed() || readbackBytes != bytes) {
                    if (readback != null) readback.close();
                    readback = RenderSystem.getDevice().createBuffer(() -> "winlandcraft-capture",
                            GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST, bytes);
                    readbackBytes = bytes;
                }
                final int fw = tw, fh = th;
                final long ft = timeUs;
                inFlight = true;
                RenderSystem.getDevice().createCommandEncoder().copyTextureToBuffer(
                        texture, readback, 0, () -> {
                            synchronized (StreamCapture.this) {
                                completedWidth = fw;
                                completedHeight = fh;
                                completedTimeUs = ft;
                                completedReady = true;
                            }
                            inFlight = false;
                        }, 0, 0, tw, th, 0);
            }
        }
        if (frame == null) return null;
        byte[] scaled = acquire(w * h * 4);
        scale(frame, sw, sh, scaled, w, h);
        recycle(frame);
        var stats = statistics(scaled);
        diagnose(stats, w, h, "browser texture, async gpu copy");
        return new Pixels(this, scaled, w, h, timeUs);
    }

    /** Maps the finished copy into a pooled array, or null while the GPU is busy. */
    private synchronized byte[] reap() {
        if (!completedReady) return null;
        completedReady = false;
        int bytes = completedWidth * completedHeight * 4;
        byte[] out = acquire(bytes);
        try (var mapped = readback.slice(0, bytes).map(true, false)) {
            mapped.data().get(out);
        } catch (RuntimeException | LinkageError failure) {
            recycle(out);
            throw failure;
        }
        return out;
    }

    private void recycle(byte[] bytes) {
        freeFrames.offer(bytes);
    }

    private byte[] acquire(int size) {
        byte[] bytes;
        while ((bytes = freeFrames.poll()) != null) if (bytes.length == size) return bytes;
        return new byte[size];
    }

    /** Bilinear downscale of top-down RGBA. */
    private static void scale(byte[] src, int sw, int sh, byte[] dst, int dw, int dh) {
        for (int y = 0; y < dh; y++) {
            float sy = (y + .5f) * sh / dh - .5f;
            int y0 = Math.clamp((int) Math.floor(sy), 0, sh - 1);
            int y1 = Math.min(y0 + 1, sh - 1);
            float fy = Math.clamp(sy - y0, 0, 1);
            for (int x = 0; x < dw; x++) {
                float sx = (x + .5f) * sw / dw - .5f;
                int x0 = Math.clamp((int) Math.floor(sx), 0, sw - 1);
                int x1 = Math.min(x0 + 1, sw - 1);
                float fx = Math.clamp(sx - x0, 0, 1);
                int di = (y * dw + x) * 4;
                for (int c = 0; c < 4; c++) {
                    float top = lerp(src, sw, x0, y0, x1, y0, fx, c);
                    float bottom = lerp(src, sw, x0, y1, x1, y1, fx, c);
                    dst[di + c] = (byte) Math.round(top + (bottom - top) * fy);
                }
            }
        }
    }

    private static float lerp(byte[] src, int stride, int x0, int y, int x1, int y1, float fx, int c) {
        float a = src[(y * stride + x0) * 4 + c] & 255;
        float b = src[(y1 * stride + x1) * 4 + c] & 255;
        return a + (b - a) * fx;
    }

    private void diagnose(Statistics stats, int width, int height, String source) {
        long now = System.currentTimeMillis();
        captures++;
        if (captures != 1 && now < nextDiagnostic) return;
        nextDiagnostic = now + 10_000;
        String summary = "source=" + source + ", frame=" + width + 'x' + height + ", sampled=" + stats.samples + ", rgb-range=" +
                stats.redMin + ".." + stats.redMax + ',' + stats.greenMin + ".." + stats.greenMax + ',' + stats.blueMin + ".." + stats.blueMax +
                ", mean=" + stats.redMean + ',' + stats.greenMean + ',' + stats.blueMean + ", hash=" + Long.toUnsignedString(stats.hash, 16);
        if (stats.solid()) WinLandCraftClient.LOGGER.warn("Stream capture is solid-colored; the receiver will see a blank frame ({})", summary);
        else WinLandCraftClient.LOGGER.info("Stream capture health: {}", summary);
    }

    private static Statistics statistics(byte[] bytes) {
        int pixels = bytes.length / 4, step = Math.max(1, pixels / 2048), samples = 0;
        int redMin = 255, redMax = 0, greenMin = 255, greenMax = 0, blueMin = 255, blueMax = 0;
        long red = 0, green = 0, blue = 0, hash = 0xcbf29ce484222325L;
        for (int pixel = 0; pixel < pixels; pixel += step) {
            int offset = pixel * 4, r = bytes[offset] & 255, g = bytes[offset + 1] & 255, b = bytes[offset + 2] & 255;
            redMin = Math.min(redMin, r);
            redMax = Math.max(redMax, r);
            greenMin = Math.min(greenMin, g);
            greenMax = Math.max(greenMax, g);
            blueMin = Math.min(blueMin, b);
            blueMax = Math.max(blueMax, b);
            red += r;
            green += g;
            blue += b;
            samples++;
            hash ^= (long) r << 16 | (long) g << 8 | b;
            hash *= 0x100000001b3L;
        }
        return new Statistics(samples, redMin, redMax, greenMin, greenMax, blueMin, blueMax, red / samples, green / samples, blue / samples, hash);
    }

    private record Statistics(int samples, int redMin, int redMax, int greenMin, int greenMax, int blueMin, int blueMax,
                              long redMean, long greenMean, long blueMean, long hash) {
        boolean solid() { return redMax - redMin <= 2 && greenMax - greenMin <= 2 && blueMax - blueMin <= 2; }
    }

    @Override public void close() {
        RenderSystem.assertOnRenderThread();
        if (readback != null) {
            readback.close();
            readback = null;
        }
        readbackBytes = 0;
        inFlight = false;
        completedReady = false;
        freeFrames.clear();
        captures = nextDiagnostic = 0;
    }
}
