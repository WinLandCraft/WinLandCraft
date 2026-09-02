package dev.winlandcraft.api.v2;
import java.nio.ByteBuffer;
/** Window-owned surface. CPU submissions and clear are thread-safe; GPU methods require the render thread. */
public interface FrameSurface {
    /** Copies bytes from buffer.position(), without changing it. Top-down, straight-alpha RGBA/BGRA. */
    boolean submit(ByteBuffer pixels,int width,int height,int rowStride,PixelFormat format);
    /** Discard pending and displayed content on the next render. */
    void clear();
    /** Optional capability; call on the host render thread. Older hosts return false. */
    default boolean supportsGpu(){return false;}
    /** Transfer ownership of a source to the host, or pass null to return to CPU frames.
     * Render-thread only. On rejection ownership stays with the caller. */
    default void gpuSource(GpuSource source){throw new UnsupportedOperationException("GPU surfaces unavailable");}
}
