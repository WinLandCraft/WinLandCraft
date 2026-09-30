package dev.winlandcraft;

import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.concurrent.ArrayBlockingQueue;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.Projection;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector4f;

/**
 * Re-renders the streaming source's full app surface (native UI plus browser,
 * plugin, and remote content alike) into a private target, then copies it
 * GPU-to-CPU asynchronously (at most one copy in flight) and scales on the CPU.
 * This restores the 1.21.4 private-FBO capture through the 26.3 submit model:
 * draws go to an owned SubmitNodeStorage executed with the shared feature
 * dispatcher in classic (non-OIT) mode, so layers composite by submit order
 * exactly like the old immediate re-render. A fake camera on the panel normal
 * with a fitted perspective frames the surface; the base rotation aligns the
 * normal to +z so facing and winding match the world pass.
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

    private static final Vector4f CLEAR = new Vector4f(0.09f, 0.12f, 0.17f, 1f);
    private static final double CAPTURE_DISTANCE = 3.0;

    private final ArrayBlockingQueue<byte[]> freeFrames = new ArrayBlockingQueue<>(4);
    private final Projection projection = new Projection();
    private ProjectionMatrixBuffer projectionBuffers;
    private GpuTexture colorTex, depthTex;
    private GpuTextureView colorView, depthView;
    private int surfaceWidth, surfaceHeight;
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
        int logicalWidth = panel.pixelWidth(), logicalHeight = panel.pixelHeight() + panel.titlebarHeight();
        int maxHeight = quality.height(), maxWidth = maxHeight * 16 / 9;
        double factor = Math.min(1, Math.min(maxWidth / (double) logicalWidth, maxHeight / (double) logicalHeight));
        int w = Math.max(2, (int) (logicalWidth * factor) & ~1), h = Math.max(2, (int) (logicalHeight * factor) & ~1);
        if (inFlight && (w != surfaceWidth || h != surfaceHeight)) return null;
        ensureSurface(w, h);
        renderSurface(panel, w, h);
        // Reap the previous async copy first so a slow GPU never queues frames.
        // Snapshot dimensions with the reap: the completion callback can fire on another thread.
        final byte[] frame;
        final int sw, sh;
        synchronized (this) {
            frame = reap();
            sw = completedWidth;
            sh = completedHeight;
        }
        if (!inFlight) {
            int bytes = w * h * 4;
            if (readback == null || readback.isClosed() || readbackBytes != bytes) {
                if (readback != null) readback.close();
                readback = RenderSystem.getDevice().createBuffer(() -> "winlandcraft-capture",
                        GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST, bytes);
                readbackBytes = bytes;
            }
            final int fw = w, fh = h;
            final long ft = timeUs;
            inFlight = true;
            RenderSystem.getDevice().createCommandEncoder().copyTextureToBuffer(
                    colorTex, readback, 0, () -> {
                        synchronized (StreamCapture.this) {
                            completedWidth = fw;
                            completedHeight = fh;
                            completedTimeUs = ft;
                            completedReady = true;
                        }
                        inFlight = false;
                    }, 0, 0, 0, w, h);
        }
        if (frame == null) return null;
        byte[] scaled = acquire(w * h * 4);
        scaleFlipped(frame, sw, sh, scaled, w, h);
        recycle(frame);
        var stats = statistics(scaled);
        diagnose(stats, w, h, "offscreen panel surface");
        return new Pixels(this, scaled, w, h, timeUs);
    }

    /** Draws the panel surface flat and exactly filling the target. Placement,
     *  facing, and curvature are ignored (replicas re-apply the curve from the
     *  stream state), so the video maps 1:1 onto every viewer with no baked
     *  border and no double bend. Identity rotation keeps the normal at +z
     *  toward the camera, matching world winding and culling. */
    private void renderSurface(WorldPanel panel, int w, int h) {
        float densityX = panel.worldWidth() / panel.pixelWidth();
        float densityY = panel.worldHeight() / Math.max(1, panel.pixelHeight());
        float halfW = panel.pixelWidth() * densityX / 2;
        float halfH = (panel.pixelHeight() + panel.titlebarHeight()) * densityY / 2;
        float shift = panel.titlebarHeight() * densityY / 2;
        float fovY = (float) Math.toDegrees(2 * Math.atan(halfH / CAPTURE_DISTANCE));
        projection.setupPerspective(0.05f, 100f, fovY, halfW * 2, halfH * 2);
        if (projectionBuffers == null) projectionBuffers = new ProjectionMatrixBuffer("winlandcraft-capture");

        var storage = new SubmitNodeStorage();
        var pose = new PoseStack();
        var camera = new CameraRenderState();
        camera.pos = Vec3.ZERO;
        var pass = new WorldPass(storage, pose, camera);
        try (var canvas = new PanelCanvas(pass,
                new Vec3(0, -shift, -CAPTURE_DISTANCE),
                new Quaternionf(), densityX, densityY, panel.pixelWidth(), panel.pixelHeight())) {
            panel.drawSurface(canvas);
        }

        var dispatcher = Minecraft.getInstance().gameRenderer.featureRenderDispatcher();
        var frame = dispatcher.prepareFrame(storage);
        RenderSystem.backupProjectionMatrix();
        var modelView = RenderSystem.getModelViewStack();
        modelView.pushMatrix();
        modelView.identity();
        try {
            RenderSystem.setProjectionMatrix(projectionBuffers.getBuffer(projection), ProjectionType.PERSPECTIVE);
            var encoder = RenderSystem.getDevice().createCommandEncoder();
            try (var renderPass = encoder.createRenderPass(() -> "winlandcraft-capture", colorView,
                    Optional.of(CLEAR), depthView, OptionalDouble.of(0.0))) {
                RenderSystem.bindDefaultUniforms(renderPass);
                FeatureRenderDispatcher.renderAllFeatures(renderPass, frame);
            }
        } finally {
            modelView.popMatrix();
            RenderSystem.restoreProjectionMatrix();
            frame.close();
        }
    }

    private void ensureSurface(int w, int h) {
        if (colorTex != null && !colorTex.isClosed() && surfaceWidth == w && surfaceHeight == h) return;
        closeSurface();
        var device = RenderSystem.getDevice();
        colorTex = device.createTexture("winlandcraft-capture",
                GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_COPY_SRC,
                GpuFormat.RGBA8_UNORM, w, h, 1, 1);
        colorView = device.createTextureView(colorTex);
        depthTex = device.createTexture("winlandcraft-capture-depth",
                GpuTexture.USAGE_RENDER_ATTACHMENT, GpuFormat.D32_FLOAT, w, h, 1, 1);
        depthView = device.createTextureView(depthTex);
        surfaceWidth = w;
        surfaceHeight = h;
    }

    private void closeSurface() {
        if (colorView != null) { colorView.close(); colorView = null; }
        if (colorTex != null) { colorTex.close(); colorTex = null; }
        if (depthView != null) { depthView.close(); depthView = null; }
        if (depthTex != null) { depthTex.close(); depthTex = null; }
        surfaceWidth = surfaceHeight = 0;
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

    /** Bilinear downscale of bottom-up GPU rows into a top-down frame. */
    private static void scaleFlipped(byte[] src, int sw, int sh, byte[] dst, int dw, int dh) {
        for (int y = 0; y < dh; y++) {
            float sy = (sh - 1) - ((y + .5f) * sh / dh - .5f);
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
        closeSurface();
        if (projectionBuffers != null) {
            projectionBuffers.close();
            projectionBuffers = null;
        }
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
