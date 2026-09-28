package dev.winlandcraft.mixin;

import dev.winlandcraft.WinLandCraftClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.network.DisconnectionDetails;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Leave hook replacing the fabric connection event. */
@Mixin(ClientCommonPacketListenerImpl.class)
public final class ClientDisconnectMixin {
    @Inject(method = "onDisconnect", at = @At("HEAD"))
    private void winlandcraft$left(DisconnectionDetails details, CallbackInfo ci) {
        // The disconnect can race a world tick; hop input and GPU teardown to the client thread.
        Minecraft.getInstance().execute(WinLandCraftClient::onDisconnect);
    }
}
