package dev.winlandcraft;

import com.cinemamod.mcef.MCEFBrowser;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import net.minecraft.client.renderer.texture.AbstractTexture;

/**
 * TextureManager entry for a live browser view. The fork owns the GPU
 * texture and reallocates it on resize; this wrapper re-syncs the view
 * every frame so world draws never reference a deleted texture.
 */
final class BrowserTexture extends AbstractTexture {
    private final MCEFBrowser browser;

    BrowserTexture(MCEFBrowser browser) {
        this.browser = browser;
        this.sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
        sync();
    }

    /** Refreshes the wrapped view after resizes. Cheap field copies; call before drawing. */
    void sync() {
        GpuTextureView view = browser.getRenderer().getTextureView();
        if (view != textureView) {
            textureView = view;
            texture = browser.getRenderer().getTexture();
        }
    }

    boolean ready() {
        return textureView != null;
    }

    void setSmoothing(boolean smooth) {
        sampler = RenderSystem.getSamplerCache().getClampToEdge(smooth ? FilterMode.LINEAR : FilterMode.NEAREST);
    }

    @Override public void close() {
        // GPU objects belong to the fork; only the TextureManager registration is released.
    }
}
