package dev.winlandcraft.api.v2;
import java.nio.file.Path;
public interface WindowContext {
    int width();
    int height();
    void title(String title);
    /** Request native text focus; WinLandCraft owns capture and Escape handling. */
    void requestKeyboard(boolean requested);
    void close();
    /** Schedule a bounded completion on the client thread; ignored after this window session closes. */
    void execute(Runnable action);
    Path dataDirectory();
    String clipboard();
    void clipboard(String text);
    /** One managed view for CHROMIUM/HYBRID; unsupported for NATIVE. */
    BrowserView browser();
    /** One CPU pixel surface, automatically fitted to the content area. Safe to submit from workers. */
    FrameSurface frames();
    /** Stereo 48 kHz PCM output, shared by local playback and window streaming. */
    AudioOutput audio();
}
