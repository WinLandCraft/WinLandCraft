package dev.winlandcraft.mixin;

import dev.winlandcraft.WinLandCraftClient;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Draws interaction hints over the HUD. Replaces the fabric HUD layer. */
@Mixin(Hud.class)
public final class HudMixin {
    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void winlandcraft$hints(GuiGraphicsExtractor graphics, DeltaTracker delta, CallbackInfo ci) {
        if (WinLandCraftClient.controls == null) return;
        var client = Minecraft.getInstance();
        WinLandCraftClient.controls.renderResizeHint(graphics);
        if (WinLandCraftClient.controls.isTyping() && !client.gui.hud.isHidden())
            graphics.text(client.font, "Typing in window | Esc to return", 8, 8, 0xFF51CFDF, true);
    }
}
