package dev.winlandcraft.mixin;

import dev.winlandcraft.WinLandCraftClient;
import dev.winlandcraft.WorldPanel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Join/leave hooks replacing the fabric connection events. */
@Mixin(ClientPacketListener.class)
public final class ClientConnectionMixin {
    @Inject(method = "handleLogin", at = @At("TAIL"))
    private void winlandcraft$joined(ClientboundLoginPacket packet, CallbackInfo ci) {
        // Channel negotiation no longer exists: assume a modded server like before.
        WinLandCraftClient.serverHasMod = true;
    }

    @Inject(method = "onDisconnect", at = @At("HEAD"))
    private void winlandcraft$left(DisconnectionDetails details, CallbackInfo ci) {
        // The disconnect can race a world tick; hop input and GPU teardown to the client thread.
        Minecraft.getInstance().execute(WinLandCraftClient::onDisconnect);
    }
}
