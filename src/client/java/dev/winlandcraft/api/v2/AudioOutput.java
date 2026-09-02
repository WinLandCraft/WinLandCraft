package dev.winlandcraft.api.v2;
/** Thread-safe window-owned output; closed sessions reject submissions. */
public interface AudioOutput {
    /** Copies interleaved L,R float PCM at 48000 Hz. 1..4800 frames; returns false on backpressure/close. */
    boolean submit(float[] stereo);
    /** Disable local monitoring when the source is already audible on the desktop. Streaming remains enabled. */
    void localPlayback(boolean enabled);
    /** Discard queued local audio and reset the submission clock. */
    void clear();
}
