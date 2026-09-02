package dev.winlandcraft.api.v2;

/** Host-owned producer after attachment. Every callback runs on Minecraft's render thread with its GL context current.
 * Restore all changed GL state. Never block waiting for a frame. */
public interface GpuSource {
    /** Acquire a complete, single-sample GL_TEXTURE_2D / RGBA8 texture visible in the current context.
     * Return null when no new frame is ready. Synchronize producer writes before returning. */
    GpuFrame acquire();
    /** Called exactly once for each non-null acquired frame, including failed copies.
     * The GPU copy has been enqueued; establish GPU completion/interop release before another context or API writes. */
    void release(GpuFrame frame);
    /** Release producer resources. Called once on detach, replacement, failure or window close. */
    void close();
}
