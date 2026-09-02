package dev.winlandcraft.mixin;

import com.cinemamod.mcef.MCEFBrowser;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.winlandcraft.WinLandCraftClient;
import net.minecraft.client.Minecraft;
import org.cef.browser.CefBrowser;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.awt.Rectangle;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.concurrent.atomic.AtomicBoolean;

/** Moves JCEF paint uploads onto Minecraft's render thread. */
@Mixin(value = MCEFBrowser.class, remap = false)
public abstract class McefPaintMixin {
    @Unique private static final int WINLANDCRAFT$MAX_PENDING_FRAMES = 3;
    @Unique private static final int WINLANDCRAFT$MAX_POOLED_BUFFERS = 4;
    @Unique private static final AtomicBoolean WINLANDCRAFT$LOGGED = new AtomicBoolean();

    @Unique private final Object winlandcraft$paintLock = new Object();
    @Unique private final ArrayDeque<PaintFrame> winlandcraft$paintFrames = new ArrayDeque<>();
    @Unique private final ArrayDeque<ByteBuffer> winlandcraft$paintBuffers = new ArrayDeque<>();
    @Unique private boolean winlandcraft$paintScheduled;
    @Unique private volatile boolean winlandcraft$paintClosed;

    @Inject(method = "onPaint", at = @At("HEAD"), cancellable = true)
    private void winlandcraft$deferPaint(CefBrowser browser, boolean popup, Rectangle[] dirtyRects,
                                         ByteBuffer source, int width, int height, CallbackInfo callback) {
        if (RenderSystem.isOnRenderThread()) return;
        callback.cancel();
        if (dirtyRects.length == 0 || winlandcraft$paintClosed) return;

        ByteBuffer pixels = winlandcraft$acquireBuffer(source.capacity(), source.order());
        ByteBuffer input = source.duplicate();
        input.clear();
        pixels.clear();
        pixels.put(input);
        pixels.flip();

        Rectangle[] rectangles = new Rectangle[dirtyRects.length];
        for (int index = 0; index < dirtyRects.length; index++) {
            rectangles[index] = new Rectangle(dirtyRects[index]);
        }
        winlandcraft$enqueuePaint(new PaintFrame(browser, popup, rectangles, pixels, width, height));
    }

    @Inject(method = "close", at = @At("HEAD"))
    private void winlandcraft$closePaintQueue(CallbackInfo callback) {
        synchronized (winlandcraft$paintLock) {
            winlandcraft$paintClosed = true;
            winlandcraft$paintFrames.clear();
            winlandcraft$paintBuffers.clear();
        }
    }

    @Unique
    private ByteBuffer winlandcraft$acquireBuffer(int capacity, java.nio.ByteOrder order) {
        synchronized (winlandcraft$paintLock) {
            for (Iterator<ByteBuffer> iterator = winlandcraft$paintBuffers.iterator(); iterator.hasNext();) {
                ByteBuffer buffer = iterator.next();
                if (buffer.capacity() == capacity) {
                    iterator.remove();
                    return buffer.order(order);
                }
            }
        }
        return ByteBuffer.allocateDirect(capacity).order(order);
    }

    @Unique
    private void winlandcraft$enqueuePaint(PaintFrame frame) {
        boolean schedule = false;
        synchronized (winlandcraft$paintLock) {
            if (winlandcraft$paintClosed) {
                winlandcraft$recycleBuffer(frame.pixels());
                return;
            }

            PaintFrame tail = winlandcraft$paintFrames.peekLast();
            if (tail != null && tail.compatibleWith(frame)) {
                winlandcraft$paintFrames.removeLast();
                frame = frame.withDirtyBounds(tail);
                winlandcraft$recycleBuffer(tail.pixels());
            } else if (winlandcraft$paintFrames.size() >= WINLANDCRAFT$MAX_PENDING_FRAMES) {
                PaintFrame superseded = winlandcraft$removeCompatibleFrame(frame);
                if (superseded == null) superseded = winlandcraft$paintFrames.removeFirst();
                if (superseded.compatibleWith(frame)) frame = frame.withDirtyBounds(superseded);
                winlandcraft$recycleBuffer(superseded.pixels());
            }
            winlandcraft$paintFrames.addLast(frame);
            if (!winlandcraft$paintScheduled) {
                winlandcraft$paintScheduled = true;
                schedule = true;
            }
        }
        if (schedule) {
            Minecraft.getInstance().execute(this::winlandcraft$drainPaint);
            if (WINLANDCRAFT$LOGGED.compareAndSet(false, true)) {
                WinLandCraftClient.LOGGER.info("Bridging Chromium paint callbacks onto Minecraft's render thread");
            }
        }
    }

    @Unique
    private PaintFrame winlandcraft$removeCompatibleFrame(PaintFrame replacement) {
        for (Iterator<PaintFrame> iterator = winlandcraft$paintFrames.iterator(); iterator.hasNext();) {
            PaintFrame queued = iterator.next();
            if (queued.compatibleWith(replacement)) {
                iterator.remove();
                return queued;
            }
        }
        return null;
    }

    @Unique
    private void winlandcraft$drainPaint() {
        PaintFrame frame;
        synchronized (winlandcraft$paintLock) {
            frame = winlandcraft$paintClosed ? null : winlandcraft$paintFrames.pollFirst();
        }

        if (frame != null) {
            try {
                ((MCEFBrowser) (Object) this).onPaint(frame.browser(), frame.popup(), frame.dirtyRects(),
                        frame.pixels(), frame.width(), frame.height());
            } finally {
                winlandcraft$recycleBuffer(frame.pixels());
            }
        }

        boolean reschedule;
        synchronized (winlandcraft$paintLock) {
            reschedule = !winlandcraft$paintClosed && !winlandcraft$paintFrames.isEmpty();
            if (!reschedule) winlandcraft$paintScheduled = false;
        }
        if (reschedule) Minecraft.getInstance().execute(this::winlandcraft$drainPaint);
    }

    @Unique
    private void winlandcraft$recycleBuffer(ByteBuffer buffer) {
        synchronized (winlandcraft$paintLock) {
            if (!winlandcraft$paintClosed && winlandcraft$paintBuffers.size() < WINLANDCRAFT$MAX_POOLED_BUFFERS) {
                buffer.clear();
                winlandcraft$paintBuffers.addLast(buffer);
            }
        }
    }

    @Unique
    private record PaintFrame(CefBrowser browser, boolean popup, Rectangle[] dirtyRects,
                              ByteBuffer pixels, int width, int height) {
        private boolean compatibleWith(PaintFrame other) {
            return popup == other.popup && width == other.width && height == other.height;
        }

        private PaintFrame withDirtyBounds(PaintFrame older) {
            Rectangle bounds = null;
            for (Rectangle rectangle : older.dirtyRects) {
                bounds = bounds == null ? new Rectangle(rectangle) : bounds.union(rectangle);
            }
            for (Rectangle rectangle : dirtyRects) {
                bounds = bounds == null ? new Rectangle(rectangle) : bounds.union(rectangle);
            }
            return new PaintFrame(browser, popup, new Rectangle[]{bounds}, pixels, width, height);
        }
    }
}
