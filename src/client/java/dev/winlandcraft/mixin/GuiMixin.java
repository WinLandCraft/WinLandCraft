package dev.winlandcraft.mixin;

import dev.winlandcraft.WinLandCraftClient;
import dev.winlandcraft.WindowCursor;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Hud.class)
public final class GuiMixin {
    @Inject(method = "extractCrosshair", at = @At("HEAD"), cancellable = true)
    private void winlandcraft$cursor(GuiGraphicsExtractor graphics, DeltaTracker delta, CallbackInfo ci) {
        if (WinLandCraftClient.controls == null) return;
        int cursor = WinLandCraftClient.controls.cursor();
        if (cursor != 0) {
            if (cursor > 0) WindowCursor.render(graphics, cursor);
            ci.cancel();
        }
    }
}
