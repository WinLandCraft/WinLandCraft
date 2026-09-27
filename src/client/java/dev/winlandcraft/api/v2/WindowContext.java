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
    /** One pixel/GPU surface, automatically fitted to the content area. CPU submissions are safe from workers. */
    FrameSurface frames();
    /** Stereo 48 kHz PCM output, shared by local playback and window streaming. */
    AudioOutput audio();
}
