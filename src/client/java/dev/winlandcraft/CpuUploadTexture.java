package dev.winlandcraft;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import net.minecraft.client.renderer.texture.AbstractTexture;

import java.nio.ByteBuffer;

/**
 * TextureManager entry for CPU-decoded RGBA frames (stream receiver, plugin
 * frames). Owns its GPU texture; reallocates on shape changes. Replaces the
 * old glTexImage2D/glTexSubImage2D upload path. Render thread only.
 */
final class CpuUploadTexture extends AbstractTexture {
    private int width, height;

    CpuUploadTexture() {
        this.sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
    }

    boolean ready() {
        return textureView != null;
    }

    int width() {
        return width;
    }

    int height() {
        return height;
    }

    void setSmoothing(boolean smooth) {
        sampler = RenderSystem.getSamplerCache().getClampToEdge(smooth ? FilterMode.LINEAR : FilterMode.NEAREST);
    }

    /**
     * Copies an RGBA frame, reallocating on shape change. The buffer is only
     * read; the caller keeps ownership.
     */
    boolean upload(ByteBuffer rgba, int w, int h) {
        if (w <= 0 || h <= 0 || rgba.remaining() < w * h * 4) return false;
        if (texture == null || texture.isClosed() || width != w || height != h) {
            closeTextures();
            texture = RenderSystem.getDevice().createTexture("winlandcraft-upload",
                    GpuTexture.USAGE_COPY_DST
                            | GpuTexture.USAGE_COPY_SRC
                            | GpuTexture.USAGE_TEXTURE_BINDING,
                    GpuFormat.RGBA8_UNORM, w, h, 1, 1);
            textureView = RenderSystem.getDevice().createTextureView(texture);
            width = w;
            height = h;
        }
        RenderSystem.getDevice().createCommandEncoder().writeToTexture(texture, rgba, 0, 0, 0, 0, w, h);
        return true;
    }

    private void closeTextures() {
        if (textureView != null) {
            textureView.close();
            textureView = null;
        }
        if (texture != null) {
            texture.close();
            texture = null;
        }
        width = height = 0;
    }

    @Override public void close() {
        closeTextures();
    }
}
