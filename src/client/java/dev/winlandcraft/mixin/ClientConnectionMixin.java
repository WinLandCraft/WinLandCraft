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

/** Join hook replacing the fabric connection event. */
@Mixin(ClientPacketListener.class)
public final class ClientConnectionMixin {
    @Inject(method = "handleLogin", at = @At("TAIL"))
    private void winlandcraft$joined(ClientboundLoginPacket packet, CallbackInfo ci) {
        // Channel negotiation no longer exists: assume a modded server like before.
        WinLandCraftClient.serverHasMod = true;
    }
}
