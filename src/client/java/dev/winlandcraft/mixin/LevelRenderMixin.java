package dev.winlandcraft.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.winlandcraft.WinLandCraftClient;
import dev.winlandcraft.WorldPass;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Submits panels and interaction overlays after vanilla world features. Replaces the fabric world-render events. */
@Mixin(LevelRenderer.class)
public final class LevelRenderMixin {
    @Inject(method = "submitFeatures", at = @At("TAIL"))
    private void winlandcraft$submitPanels(LevelRenderState levelRenderState, SubmitNodeCollector collector, boolean renderOutline, CallbackInfo ci) {
        if (WinLandCraftClient.controls == null || WinLandCraftClient.apps == null) return;
        var client = Minecraft.getInstance();
        if (client.level == null || client.player == null) return;
        var pass = new WorldPass(collector, new PoseStack(), levelRenderState.cameraRenderState);
        WinLandCraftClient.controls.update(client.gameRenderer.mainCamera());
        WinLandCraftClient.apps.windows.forEach(window -> window.render(pass));
        WinLandCraftClient.controls.renderLaser(pass);
        WinLandCraftClient.controls.renderHandles(pass);
        WinLandCraftClient.controls.renderBeacons(pass);
    }
}
