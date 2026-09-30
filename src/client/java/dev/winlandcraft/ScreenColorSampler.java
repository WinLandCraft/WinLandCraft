package dev.winlandcraft;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.textures.GpuTexture;

import java.nio.ByteBuffer;
import java.util.function.Consumer;

/**
 * Asynchronous six-zone sampling from a browser texture. The old mip-chain
 * PBO path is gone with immediate mode: the full frame is copied to a mapped
 * buffer instead (at most one in flight) and averaged on the CPU.
 */
final class ScreenColorSampler implements AutoCloseable {
    private static final long INTERVAL = 500_000_000L;
    private static final int COLUMNS = 3, ROWS = 2;
    private static final float BLACK_LEVEL = .002f, FULL_OUTPUT = .03f, SATURATION = 1.5f;
    private final Slot[] slots = {new Slot(), new Slot()};
    private int cursor;
    private long nextCapture;

    void capture(GpuTexture texture, Consumer<float[]> result) {
        RenderSystem.assertOnRenderThread();
        poll(result);
        long now = System.nanoTime();
        if (now < nextCapture) return;
        if (texture == null || texture.isClosed()) return;
        int width = texture.getWidth(0), height = texture.getHeight(0);
        int bytes = width * height * 4;
        if (width <= 0 || height <= 0 || bytes <= 0) return;
        Slot slot = freeSlot();
        if (slot == null) return;
        nextCapture = now + INTERVAL;
        if (slot.buffer == null || slot.buffer.isClosed() || slot.bytes != bytes) {
            if (slot.buffer != null) slot.buffer.close();
            slot.buffer = RenderSystem.getDevice().createBuffer(() -> "winlandcraft-screenlight",
                    GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST, bytes);
            slot.bytes = bytes;
        }
        slot.width = width;
        slot.height = height;
        slot.ready = false;
        final Slot issued = slot;
        RenderSystem.getDevice().createCommandEncoder().copyTextureToBuffer(
                texture, slot.buffer, 0, () -> {
                    synchronized (issued) {
                        issued.ready = true;
                    }
                }, 0, 0, 0, width, height);
        cursor = (cursor + 1) % slots.length;
    }

    private Slot freeSlot() {
        for (int offset = 0; offset < slots.length; offset++) {
            Slot slot = slots[(cursor + offset) % slots.length];
            synchronized (slot) {
                if (!slot.issued) {
                    slot.issued = true;
                    return slot;
                }
            }
        }
        return null;
    }

    private void poll(Consumer<float[]> result) {
        for (Slot slot : slots) {
            boolean ready;
            synchronized (slot) {
                ready = slot.ready;
                slot.ready = false;
            }
            if (!ready) continue;
            try (var mapped = slot.buffer.slice(0, slot.bytes).map(true, false)) {
                ByteBuffer pixels = mapped.data();
                byte[] copy = new byte[slot.bytes];
                pixels.get(copy);
                result.accept(zones(ByteBuffer.wrap(copy), slot.width, slot.height));
            } catch (RuntimeException | LinkageError failure) {
                WinLandCraftClient.LOGGER.debug("Screen-light sample failed", failure);
            }
            synchronized (slot) {
                slot.issued = false;
            }
        }
    }

    static float[] zones(ByteBuffer pixels, int width, int height) {
        float[] colors = new float[COLUMNS * ROWS * 3];
        // Stride the average on large frames: ambient light needs no per-pixel
        // accuracy, and the sRGB->linear pow per pixel is the hot loop. Small
        // frames (like the 6x4 regression image) still sample every pixel.
        int stride = Math.max(1, Math.min(width, height) / 240);
        for (int row = 0; row < ROWS; row++) for (int column = 0; column < COLUMNS; column++) {
            int x0 = column * width / COLUMNS, x1 = Math.max(x0 + 1, (column + 1) * width / COLUMNS);
            int y0 = row * height / ROWS, y1 = Math.max(y0 + 1, (row + 1) * height / ROWS);
            float red = 0, green = 0, blue = 0;
            int count = 0;
            for (int y = y0; y < Math.min(y1, height); y += stride) for (int x = x0; x < Math.min(x1, width); x += stride) {
                int offset = (y * width + x) * 4;
                red += linear(pixels.get(offset) & 255);
                green += linear(pixels.get(offset + 1) & 255);
                blue += linear(pixels.get(offset + 2) & 255);
                count++;
            }
            int target = (row * COLUMNS + column) * 3;
            calibrate(colors, target, red / count, green / count, blue / count);
        }
        return colors;
    }

    static void calibrate(float[] output, int offset, float red, float green, float blue) {
        float luminance = red * .2126f + green * .7152f + blue * .0722f;
        float gate = smoothstep(BLACK_LEVEL, FULL_OUTPUT, luminance);
        float saturatedRed = Math.max(0, luminance + (red - luminance) * SATURATION);
        float saturatedGreen = Math.max(0, luminance + (green - luminance) * SATURATION);
        float saturatedBlue = Math.max(0, luminance + (blue - luminance) * SATURATION);
        float saturatedLuminance = saturatedRed * .2126f + saturatedGreen * .7152f + saturatedBlue * .0722f;
        float scale = saturatedLuminance == 0 ? 0 : luminance / saturatedLuminance * gate;
        output[offset] = saturatedRed * scale;
        output[offset + 1] = saturatedGreen * scale;
        output[offset + 2] = saturatedBlue * scale;
    }

    private static float smoothstep(float low, float high, float value) {
        float unit = Math.clamp((value - low) / (high - low), 0, 1);
        return unit * unit * (3 - 2 * unit);
    }

    private static float linear(int value) {
        float encoded = value / 255f;
        return encoded <= .04045f ? encoded / 12.92f : (float) Math.pow((encoded + .055f) / 1.055f, 2.4);
    }

    @Override public void close() {
        RenderSystem.assertOnRenderThread();
        for (Slot slot : slots) {
            if (slot.buffer != null) {
                slot.buffer.close();
                slot.buffer = null;
            }
            slot.bytes = 0;
        }
    }

    private static final class Slot {
        GpuBuffer buffer;
        int width, height, bytes;
        boolean issued, ready;
    }
}
