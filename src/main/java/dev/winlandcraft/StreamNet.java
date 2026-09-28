package dev.winlandcraft;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload.TypeAndCodec;
import net.minecraft.server.level.ServerPlayer;

/**
 * Vanilla transport for the stream relay. Fabric's networking API cannot be
 * used on 26.x: its 26.3 build leaks intermediary names that do not exist in
 * the unobfuscated game, so payload registration, dispatch, and send/receive
 * go through vanilla packet classes plus small mixins instead.
 */
public final class StreamNet {
    private StreamNet() {
    }

    /**
     * Appends our play-phase C2S payload types to the vanilla codec list.
     * Called from the packet-codec mixin while the vanilla codec is built.
     */
    public static List<TypeAndCodec<? super FriendlyByteBuf, ?>> c2sTypes(List<TypeAndCodec<? super FriendlyByteBuf, ?>> vanilla) {
        var types = new ArrayList<TypeAndCodec<? super FriendlyByteBuf, ?>>(vanilla);
        types.add(new TypeAndCodec<>(StreamProtocol.State.TYPE, StreamProtocol.State.CODEC));
        types.add(new TypeAndCodec<>(StreamProtocol.Frame.TYPE, StreamProtocol.Frame.CODEC));
        types.add(new TypeAndCodec<>(StreamProtocol.Stop.TYPE, StreamProtocol.Stop.CODEC));
        types.add(new TypeAndCodec<>(StreamProtocol.Control.TYPE, StreamProtocol.Control.CODEC));
        types.add(new TypeAndCodec<>(RequestTasksPayload.TYPE, RequestTasksPayload.CODEC));
        return types;
    }

    /**
     * Appends our play-phase S2C payload types to the vanilla codec list.
     * Called from the packet-codec mixin while the vanilla codec is built.
     */
    public static List<TypeAndCodec<? super RegistryFriendlyByteBuf, ?>> s2cTypes(List<TypeAndCodec<? super RegistryFriendlyByteBuf, ?>> vanilla) {
        var types = new ArrayList<TypeAndCodec<? super RegistryFriendlyByteBuf, ?>>(vanilla);
        types.add(new TypeAndCodec<>(StreamProtocol.State.TYPE, StreamProtocol.State.CODEC));
        types.add(new TypeAndCodec<>(StreamProtocol.Demand.TYPE, StreamProtocol.Demand.CODEC));
        types.add(new TypeAndCodec<>(StreamProtocol.Frame.TYPE, StreamProtocol.Frame.CODEC));
        types.add(new TypeAndCodec<>(StreamProtocol.Stop.TYPE, StreamProtocol.Stop.CODEC));
        types.add(new TypeAndCodec<>(StreamProtocol.Control.TYPE, StreamProtocol.Control.CODEC));
        return types;
    }

    /** Sends a play-phase payload to one player. Call only on the server thread. */
    public static void sendToPlayer(ServerPlayer player, CustomPacketPayload payload) {
        player.connection.send(new ClientboundCustomPayloadPacket(payload));
    }

    /**
     * Closed-ecosystem stand-in for channel negotiation: both sides run this
     * mod when streams exist, so an open connection is sufficient. Payload
     * contents are still fully validated on receipt.
     */
    public static boolean canSendToPlayer(ServerPlayer player) {
        return player.connection != null;
    }
}
