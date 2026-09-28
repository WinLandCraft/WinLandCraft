package dev.winlandcraft.mixin;

import dev.winlandcraft.ModSettings;
import dev.winlandcraft.ModSettingsScreen;
import dev.winlandcraft.PanelRotation;
import dev.winlandcraft.WindowGroups;
import dev.winlandcraft.WinLandCraftClient;
import dev.winlandcraft.WorldPanel;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Adds the WinLandCraft settings button. Replaces the fabric screen event. */
@Mixin(OptionsScreen.class)
public final class OptionsScreenMixin {
    @Inject(method = "init", at = @At("TAIL"))
    private void winlandcraft$settings(CallbackInfo ci) {
        var screen = (OptionsScreen) (Object) this;
        if (WinLandCraftClient.controls == null || WinLandCraftClient.apps == null) return;
        screen.addRenderableWidget(net.minecraft.client.gui.components.Button.builder(Component.literal("WinLandCraft..."), button -> {
            var client = net.minecraft.client.Minecraft.getInstance();
            client.gui.setScreen(new ModSettingsScreen(screen, () -> {
                WinLandCraftClient.controls.cancel();
                WinLandCraftClient.apps.reconcileWebApps();
                if (!ModSettings.freePanelRotation) {
                    var adjusted = new java.util.HashSet<WorldPanel>();
                    for (WorldPanel panel : WinLandCraftClient.apps.windows)
                        if (panel.canInteract() && panel.isOpen() && adjusted.add(panel)) {
                            var oldRotation = new org.joml.Quaternionf(panel.orientation);
                            panel.orientation = PanelRotation.upright(panel.orientation);
                            WindowGroups.moved(panel, panel.position, oldRotation);
                            adjusted.addAll(WindowGroups.members(panel));
                        }
                    WinLandCraftClient.apps.syncAttachments();
                }
            }));
        }).bounds(8, 8, 120, 20).build());
    }
}
