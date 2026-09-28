package dev.winlandcraft.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.winlandcraft.LaserPointer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.world.item.ItemDisplayContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Captures the held laser-item pose and draws the beam in the submitting pass. */
@Mixin(ItemStackRenderState.class)
public final class LaserItemSubmitMixin {
    @Shadow
    ItemDisplayContext displayContext;

    @Inject(method = "submit", at = @At("HEAD"))
    private void winlandcraft$calibrateLaserModel(PoseStack pose, SubmitNodeCollector collector,
                                                  int lightCoords, int overlayCoords, int outlineColor,
                                                  CallbackInfo ci) {
        LaserPointer.beginHandRender(displayContext);
        LaserPointer.captureAndDraw(pose, collector, displayContext);
    }
}
