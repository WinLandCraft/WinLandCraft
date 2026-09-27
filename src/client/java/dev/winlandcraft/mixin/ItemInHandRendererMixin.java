package dev.winlandcraft.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.winlandcraft.LaserPointer;
import dev.winlandcraft.WinLandCraft;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ItemInHandRenderer.class)
public final class ItemInHandRendererMixin {
    @Inject(method="renderHandsWithItems",at=@At("HEAD"))
    private void winlandcraft$beginLaserHands(float partialTick,PoseStack pose,
                                              MultiBufferSource.BufferSource buffers,LocalPlayer player,
                                              int light,CallbackInfo ci) {
        LaserPointer.beginHands(pose);
    }

    @Inject(method="renderHandsWithItems",at=@At("RETURN"))
    private void winlandcraft$endLaserHands(float partialTick,PoseStack pose,
                                            MultiBufferSource.BufferSource buffers,LocalPlayer player,
                                            int light,CallbackInfo ci) {
        LaserPointer.endHands();
    }

    @Inject(method="renderItem",at=@At("HEAD"))
    private void winlandcraft$beginLaserRender(LivingEntity entity,ItemStack stack,ItemDisplayContext context,
                                               boolean leftHand,PoseStack pose,MultiBufferSource buffers,
                                               int light,CallbackInfo ci) {
        if(stack.is(WinLandCraft.LASER_POINTER))LaserPointer.beginHandRender(context);
    }

    @Inject(method="renderItem",at=@At("RETURN"))
    private void winlandcraft$endLaserRender(LivingEntity entity,ItemStack stack,ItemDisplayContext context,
                                             boolean leftHand,PoseStack pose,MultiBufferSource buffers,
                                             int light,CallbackInfo ci) {
        if(stack.is(WinLandCraft.LASER_POINTER))LaserPointer.endHandRender();
    }
}
