package dev.winlandcraft.api.v2;
import java.nio.file.Path;
/** All callbacks run on the client/render thread. Never block them on disk or network I/O. */
public interface App {
    default void onOpen(WindowContext window) { }
    default void onClose() { }
    default void onResize(int width,int height) { }
    default void onTick() { }
    default void render(Canvas canvas) { }
    default void onPointerMove(int x,int y) { }
    default void onPointerDown(int x,int y,int button) { }
    default void onPointerUp(int x,int y,int button) { }
    default void onScroll(int x,int y,double amount) { }
    default void onKey(int key,int scanCode,int action,int modifiers) { }
    default void onCharacter(char character,int modifiers) { }
    default void onFocusChanged(boolean focused) { }
    default void openFile(Path path) { }
    default Path dragFileAt(int x,int y) { return null; }
    default void onBrowserLoaded() { }
    default void onBrowserAddressChanged(String address) { }
    default void onBrowserTitleChanged(String title) { }
}
