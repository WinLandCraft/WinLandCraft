package dev.winlandcraft.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.winlandcraft.LaserPointer;
import net.minecraft.client.renderer.FirstPersonHandsAndItemsRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.FirstPersonHandsAndItemsRenderState;
import net.minecraft.client.renderer.state.level.PlayerRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(FirstPersonHandsAndItemsRenderer.class)
public final class ItemInHandRendererMixin {
    @Inject(method = "submitHandsWithItems", at = @At("HEAD"))
    private void winlandcraft$beginLaserHands(float partialTick, PoseStack pose, SubmitNodeCollector collector,
                                              PlayerRenderState playerState, FirstPersonHandsAndItemsRenderState state,
                                              CallbackInfo ci) {
        LaserPointer.beginHands(pose);
    }

    @Inject(method = "submitHandsWithItems", at = @At("RETURN"))
    private void winlandcraft$endLaserHands(float partialTick, PoseStack pose, SubmitNodeCollector collector,
                                            PlayerRenderState playerState, FirstPersonHandsAndItemsRenderState state,
                                            CallbackInfo ci) {
        LaserPointer.endHands();
        LaserPointer.endHandRender();
    }
}
