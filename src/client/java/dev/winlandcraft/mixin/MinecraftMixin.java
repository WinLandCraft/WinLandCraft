package dev.winlandcraft.mixin;

import dev.winlandcraft.WinLandCraft;
import dev.winlandcraft.WinLandCraftClient;
import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Minecraft.class)
public final class MinecraftMixin {
    @Inject(method = "startAttack", at = @At("HEAD"), cancellable = true)
    private void winlandcraft$attack(CallbackInfoReturnable<Boolean> ci) {
        if (WinLandCraftClient.controls != null && WinLandCraftClient.controls.blocksWorldActions()) ci.setReturnValue(false);
    }
    @Inject(method = "continueAttack", at = @At("HEAD"), cancellable = true)
    private void winlandcraft$mine(boolean leftClick, CallbackInfo ci) {
        if (WinLandCraftClient.controls != null && WinLandCraftClient.controls.blocksWorldActions()) {
            var client = Minecraft.getInstance();
            if (client.gameMode != null) client.gameMode.stopDestroyBlock();
            ci.cancel();
        }
    }
    @Inject(method = "startUseItem", at = @At("HEAD"), cancellable = true)
    private void winlandcraft$use(CallbackInfo ci) {
        var client = Minecraft.getInstance();
        if(client.player==null)return;
        InteractionHand laserHand=client.player.getMainHandItem().is(WinLandCraft.LASER_POINTER)
                ?InteractionHand.MAIN_HAND:client.player.getMainHandItem().isEmpty()
                &&client.player.getOffhandItem().is(WinLandCraft.LASER_POINTER)?InteractionHand.OFF_HAND:null;
        if(laserHand!=null&&WinLandCraftClient.controls!=null) {
            WinLandCraftClient.controls.use(client,laserHand);
            ci.cancel();
            return;
        }
        if(WinLandCraft.isControl(client.player.getMainHandItem())
                ||client.player.getMainHandItem().isEmpty()&&WinLandCraft.isControl(client.player.getOffhandItem()))return;
        if (WinLandCraftClient.controls != null && WinLandCraftClient.controls.blocksWorldActions()) ci.cancel();
    }
}
