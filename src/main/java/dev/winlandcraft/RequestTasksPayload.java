package dev.winlandcraft;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record RequestTasksPayload() implements CustomPacketPayload {
    public static final RequestTasksPayload INSTANCE = new RequestTasksPayload();
    public static final Type<RequestTasksPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath("winlandcraft", "request_tasks"));
    public static final StreamCodec<RegistryFriendlyByteBuf, RequestTasksPayload> CODEC = StreamCodec.unit(INSTANCE);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
