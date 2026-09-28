package dev.winlandcraft.mixin;

import dev.winlandcraft.StreamRelay;
import dev.winlandcraft.WinLandCraft;
import net.minecraft.network.Connection;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Join/leave hooks replacing the fabric connection events. Runs on both sides; fires on the server. */
@Mixin(PlayerList.class)
public final class StreamServerJoinMixin {
    @Inject(method = "placeNewPlayer", at = @At("TAIL"))
    private void winlandcraft$joined(Connection connection, ServerPlayer player, CommonListenerCookie cookie, CallbackInfo ci) {
        WinLandCraft.removeLegacyInteract(player);
    }

    @Inject(method = "remove", at = @At("HEAD"))
    private void winlandcraft$left(ServerPlayer player, CallbackInfo ci) {
        StreamRelay.onDisconnect(player);
    }
}
