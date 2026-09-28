package dev.winlandcraft.mixin;

import dev.winlandcraft.StreamClient;
import dev.winlandcraft.StreamProtocol;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Receives relay payloads on the client. Vanilla only warns about them. */
@Mixin(ClientPacketListener.class)
public final class StreamClientMixin {
    @Inject(method = "handleCustomPayload", at = @At("HEAD"), cancellable = true)
    private void winlandcraft$receive(CustomPacketPayload payload, CallbackInfo ci) {
        boolean ours = payload instanceof StreamProtocol.State || payload instanceof StreamProtocol.Demand
                || payload instanceof StreamProtocol.Frame || payload instanceof StreamProtocol.Stop;
        if (!ours) return;
        ci.cancel();
        // Hop onto the render thread; Netty must never touch client state directly.
        Minecraft.getInstance().submit(() -> StreamClient.receive(payload));
    }
}
