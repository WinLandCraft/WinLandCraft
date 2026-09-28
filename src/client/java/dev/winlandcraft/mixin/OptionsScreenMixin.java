package dev.winlandcraft.mixin;

import dev.winlandcraft.WinLandCraftClient;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Adds the WinLandCraft settings button. Replaces the fabric screen event. */
@Mixin(OptionsScreen.class)
public abstract class OptionsScreenMixin extends Screen {
    protected OptionsScreenMixin() {
        super(Component.empty());
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void winlandcraft$settings(CallbackInfo ci) {
        if (WinLandCraftClient.controls == null || WinLandCraftClient.apps == null) return;
        var screen = (OptionsScreen) (Object) this;
        addRenderableWidget(net.minecraft.client.gui.components.Button.builder(
                net.minecraft.network.chat.Component.literal("WinLandCraft..."),
                button -> WinLandCraftClient.openSettings(screen)).bounds(8, 8, 120, 20).build());
    }
}
