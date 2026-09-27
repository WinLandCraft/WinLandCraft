package dev.winlandcraft.mixin;

import dev.winlandcraft.ScreenLighting;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Uploads screen lights before Iris claims the world-rendering GL state. */
@Mixin(GameRenderer.class)
public final class GameRendererLightMixin {
    @Inject(method="renderLevel",at=@At(value="INVOKE",
            target="Lnet/minecraft/client/Camera;setup(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/world/entity/Entity;ZZF)V",
            shift=At.Shift.AFTER))
    private void winlandcraft$uploadScreenLights(DeltaTracker delta, CallbackInfo callback) {
        ScreenLighting.uploadFrame(((GameRenderer)(Object)this).getMainCamera());
    }
}
