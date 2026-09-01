package dev.winlandcraft.mixin;

import dev.winlandcraft.WinLandCraftClient;
import dev.winlandcraft.WindowCursor;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Gui.class)
public final class GuiMixin {
    @Inject(method = "renderCrosshair", at = @At("HEAD"), cancellable = true)
    private void winlandcraft$cursor(GuiGraphics graphics, DeltaTracker delta, CallbackInfo ci) {
        if (WinLandCraftClient.controls == null) return;
        int cursor = WinLandCraftClient.controls.cursor();
        if (cursor != 0) { WindowCursor.render(graphics, cursor); ci.cancel(); }
    }
}
