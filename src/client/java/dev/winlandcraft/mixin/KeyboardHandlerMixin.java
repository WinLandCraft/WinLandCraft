package dev.winlandcraft.mixin;

import dev.winlandcraft.WinLandCraftClient;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(KeyboardHandler.class)
public final class KeyboardHandlerMixin {
    @Inject(method = "keyPress", at = @At("HEAD"), cancellable = true)
    private void winlandcraft$key(long handle, int action, KeyEvent event, CallbackInfo ci) {
        // Internal convention: key is the SDL keycode, scan the SDL scancode.
        if (handle != 0 && handle == Minecraft.getInstance().getWindow().handle() && WinLandCraftClient.controls != null
                && WinLandCraftClient.controls.key(event.keycode(), event.key(), action, event.modifiers())) ci.cancel();
    }
    @Inject(method = "charTyped", at = @At("HEAD"), cancellable = true)
    private void winlandcraft$character(long handle, CharacterEvent event, CallbackInfo ci) {
        // The 26.x character event carries no modifiers; SDL keymods arrive through key().
        if (handle != 0 && handle == Minecraft.getInstance().getWindow().handle() && WinLandCraftClient.controls != null
                && WinLandCraftClient.controls.character(event.codepoint(), 0)) ci.cancel();
    }
}
