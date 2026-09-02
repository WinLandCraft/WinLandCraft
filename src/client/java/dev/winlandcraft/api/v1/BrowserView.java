package dev.winlandcraft.api.v1;
public interface BrowserView {
    void navigate(String url);
    String address();
    String title();
    boolean ready();
    void back();
    void forward();
    void reload();
    /** Returns false until the native view exists; JavaScript results are not returned. */
    boolean executeJavaScript(String script);
    /** Window-local pixels. Input is automatically routed to this rectangle. */
    void bounds(int x,int y,int width,int height);
    /** Restore automatic full-window bounds. */
    void fillWindow();
}
