package dev.winlandcraft.mixin;

import dev.winlandcraft.WinLandCraftClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.input.MouseButtonInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MouseHandler.class)
public final class MouseHandlerMixin {
    @Inject(method = "onButton", at = @At("HEAD"), cancellable = true)
    private void winlandcraft$button(long handle, MouseButtonInfo info, int action, CallbackInfo ci) {
        if (handle != 0 && handle == Minecraft.getInstance().getWindow().handle() && WinLandCraftClient.controls != null
                && WinLandCraftClient.controls.mouseButton(info.button(), action)) ci.cancel();
    }
    @Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
    private void winlandcraft$scroll(long window, double horizontal, double vertical, CallbackInfo ci) {
        if (window == Minecraft.getInstance().getWindow().handle() && WinLandCraftClient.controls != null
                && WinLandCraftClient.controls.scroll(vertical)) ci.cancel();
    }
}
