package dev.winlandcraft;

import com.cinemamod.mcef.MCEF;
import com.cinemamod.mcef.MCEFBrowser;
import net.minecraft.client.Minecraft;
import java.util.function.Consumer;

/** One temporary CEF view, closed on the render thread on completion, timeout, or cancel. */
final class WebAppIconProbe {
    private static WebAppIconProbe current;
    static void cancelCurrent() { if (current != null) current.cancel(); }
    private final String url;
    private final Consumer<byte[]> complete;
    private final long deadline = System.nanoTime() + 20_000_000_000L;
    private MCEFBrowser browser;
    private boolean done, reading;
    private long readyAt;
    private Thread download;
    WebAppIconProbe(String url, Consumer<byte[]> complete) {
        cancelCurrent(); this.url = url; this.complete = complete; current = this;
    }
    void tick() {
        if (done) return;
        if (System.nanoTime() >= deadline) { finish(null); return; }
        if (browser == null && !reading && MCEF.isInitialized()) {
            try {
                browser = MCEF.createBrowser(url, false, 1280, 720);
                browser.setCursorChangeListener(cursor -> { }); browser.setFocus(false);
                BrowserEvents.add(browser, new BrowserEvents.Listener() {
                    @Override public void loaded() { readyAt = System.nanoTime() + 500_000_000L; }
                });
                readyAt = System.nanoTime() + 2_000_000_000L;
            } catch (RuntimeException | LinkageError e) { finish(null); return; }
        }
        if (browser != null && !reading && System.nanoTime() >= readyAt && !browser.isLoading()) {
            reading = true;
            String page = browser.getURL();
            browser.getSource(html -> Minecraft.getInstance().execute(() -> {
                if (done) return;
                closeBrowser();
                download = Thread.startVirtualThread(() -> {
                    byte[] png = FaviconLoader.load(page, html);
                    Minecraft.getInstance().execute(() -> { if (!done) finish(png); });
                });
            }));
        }
    }
    private void closeBrowser() {
        com.mojang.blaze3d.systems.RenderSystem.assertOnRenderThread();
        if (browser != null) { BrowserEvents.remove(browser); browser.close(); browser = null; }
    }
    private void finish(byte[] png) {
        if (done) return;
        cancel(); complete.accept(png);
    }
    void cancel() {
        done = true;
        if (current == this) current = null;
        closeBrowser();
        if (download != null) { download.interrupt(); download = null; }
    }
}
