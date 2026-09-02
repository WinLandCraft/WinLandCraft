package dev.winlandcraft.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.winlandcraft.LaserPointer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.block.model.ItemTransform;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ItemStackRenderState.class)
public abstract class ItemStackRenderStateMixin {
    @Shadow public abstract ItemTransform transform();

    @Inject(method="render",at=@At("HEAD"))
    private void winlandcraft$captureLaserTransform(PoseStack pose,MultiBufferSource buffers,int light,int overlay,
                                                    CallbackInfo ci) {
        LaserPointer.captureModelTransform(pose,transform(),buffers);
    }
}
