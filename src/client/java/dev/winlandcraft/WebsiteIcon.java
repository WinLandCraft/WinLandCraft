package dev.winlandcraft;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import org.cef.browser.CefBrowser;

/** Texture lifetime and navigation generation belong to the Minecraft thread. */
final class WebsiteIcon {
    private final ResourceLocation texture;
    WebsiteIcon(int id) {
        texture = ResourceLocation.fromNamespaceAndPath("winlandcraft", "website_icon_" + id);
    }
    private long generation;
    private boolean loaded;
    private Thread download;

    ResourceLocation texture() { return loaded ? texture : null; }

    void clear() {
        generation++;
        if (download != null) { download.interrupt(); download = null; }
        if (loaded) Minecraft.getInstance().getTextureManager().release(texture);
        loaded = false;
    }

    void refresh(CefBrowser browser) {
        clear();
        long requested = generation;
        String page = browser.getURL();
        browser.getSource(html -> Minecraft.getInstance().execute(() -> {
            if (requested != generation) return;
            download = Thread.startVirtualThread(() -> {
                byte[] png = FaviconLoader.load(page, html);
                if (png == null) return;
                Minecraft.getInstance().execute(() -> {
                    if (requested != generation) return;
                    try {
                        var image = NativeImage.read(png);
                        Minecraft.getInstance().getTextureManager().register(texture, new DynamicTexture(image));
                        loaded = true;
                    } catch (Exception error) {
                        WinLandCraftClient.LOGGER.debug("Could not upload website icon", error);
                    }
                });
            });
        }));
    }
}
