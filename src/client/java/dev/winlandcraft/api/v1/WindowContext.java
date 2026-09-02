package dev.winlandcraft.api.v1;
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
}
