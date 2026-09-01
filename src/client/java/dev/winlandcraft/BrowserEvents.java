package dev.winlandcraft;

import com.cinemamod.mcef.MCEF;
import net.minecraft.client.Minecraft;
import org.cef.browser.*;
import org.cef.callback.*;
import org.cef.handler.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** One shared handler set; closing a view removes all references to its owner. */
final class BrowserEvents {
    interface Listener {
        default void address(String url) { }
        default void title(String title) { }
        default void loaded() { }
        default void menu(int x, int y, String link) { }
        default void popup(String url) { }
    }
    private static final ConcurrentHashMap<CefBrowser, Listener> owners = new ConcurrentHashMap<>();
    private static boolean installed;
    static void add(CefBrowser browser, Listener listener) {
        if (!installed) install();
        owners.put(browser, listener);
    }
    static void remove(CefBrowser browser) { owners.remove(browser); }
    private static void dispatch(CefBrowser browser, Consumer<Listener> action) {
        Listener listener = owners.get(browser);
        if (listener != null) Minecraft.getInstance().execute(() -> {
            if (owners.get(browser) == listener) action.accept(listener);
        });
    }
    private static void install() {
        MCEF.getClient().addDisplayHandler(new CefDisplayHandlerAdapter() {
            @Override public void onAddressChange(CefBrowser b, CefFrame f, String url) {
                if (f.isMain()) dispatch(b, l -> l.address(url));
            }
            @Override public void onTitleChange(CefBrowser b, String title) { dispatch(b, l -> l.title(title)); }
        });
        MCEF.getClient().addLoadHandler(new CefLoadHandlerAdapter() {
            @Override public void onLoadEnd(CefBrowser b, CefFrame f, int status) {
                if (f.isMain()) dispatch(b, Listener::loaded);
            }
        });
        MCEF.getClient().addContextMenuHandler(new CefContextMenuHandlerAdapter() {
            @Override public void onBeforeContextMenu(CefBrowser b, CefFrame f, CefContextMenuParams p, CefMenuModel model) {
                if (!owners.containsKey(b)) return;
                model.clear();
                int x = p.getXCoord(), y = p.getYCoord(); String link = p.getLinkUrl();
                dispatch(b, l -> l.menu(x, y, link));
            }
        });
        MCEF.getClient().getHandle().addLifeSpanHandler(new CefLifeSpanHandlerAdapter() {
            @Override public boolean onBeforePopup(CefBrowser b, CefFrame f, String url, String name) {
                if (!owners.containsKey(b)) return false;
                dispatch(b, l -> l.popup(url)); return true;
            }
        });
        installed = true;
    }
}
