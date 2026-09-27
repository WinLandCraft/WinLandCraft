package dev.winlandcraft.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.winlandcraft.LaserPointer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ItemStackRenderState.LayerRenderState.class)
public final class ItemStackLayerRenderStateMixin {
    @Inject(method="render",at=@At(value="INVOKE",
            target="Lnet/minecraft/client/renderer/block/model/ItemTransform;apply(ZLcom/mojang/blaze3d/vertex/PoseStack;)V",
            shift=At.Shift.AFTER))
    private void winlandcraft$calibrateLaserModel(PoseStack pose,MultiBufferSource buffers,int light,int overlay,
                                                  CallbackInfo ci) {
        LaserPointer.applyCalibration(pose);
    }
}
