package dev.winlandcraft.mixin;

import dev.winlandcraft.RequestTasksPayload;
import dev.winlandcraft.StreamProtocol;
import dev.winlandcraft.StreamRelay;
import dev.winlandcraft.WinLandCraft;
import net.minecraft.network.protocol.PacketUtils;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Receives relay payloads on the dedicated server. Vanilla ignores them. */
@Mixin(ServerGamePacketListenerImpl.class)
public final class StreamServerMixin {
    @Shadow
    public ServerPlayer player;

    @Inject(method = "handleCustomPayload", at = @At("HEAD"))
    private void winlandcraft$receive(ServerboundCustomPayloadPacket packet, CallbackInfo ci) {
        var payload = packet.payload();
        boolean ours = payload instanceof StreamProtocol.State || payload instanceof StreamProtocol.Frame
                || payload instanceof StreamProtocol.Stop || payload instanceof StreamProtocol.Control
                || payload instanceof RequestTasksPayload;
        if (!ours) return;
        // Packets arrive on Netty; reschedule onto the server thread like vanilla handlers.
        PacketUtils.ensureRunningOnSameThread(packet, (ServerGamePacketListenerImpl) (Object) this, player.level());
        if (payload instanceof StreamProtocol.State state) StreamRelay.receiveState(player, state);
        else if (payload instanceof StreamProtocol.Frame frame) StreamRelay.receiveFrame(player, frame);
        else if (payload instanceof StreamProtocol.Stop stop) StreamRelay.receiveStop(player, stop);
        else if (payload instanceof StreamProtocol.Control control) StreamRelay.receiveControl(player, control);
        else if (payload instanceof RequestTasksPayload) WinLandCraft.receiveTasks(player);
    }
}
