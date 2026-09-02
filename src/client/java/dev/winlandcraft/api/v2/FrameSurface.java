package dev.winlandcraft.api.v2;
import java.nio.ByteBuffer;
/** Window-owned, latest-frame mailbox. All methods are thread-safe. */
public interface FrameSurface {
    /** Copies bytes from buffer.position(), without changing it. Top-down, straight-alpha RGBA/BGRA. */
    boolean submit(ByteBuffer pixels,int width,int height,int rowStride,PixelFormat format);
    /** Discard pending and displayed content on the next render. */
    void clear();
}
