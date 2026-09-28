package dev.winlandcraft.mixin;

import dev.winlandcraft.StreamNet;
import java.util.List;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload.TypeAndCodec;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** Extends the vanilla C2S payload codec with the relay types. Runs on both sides. */
@Mixin(ServerboundCustomPayloadPacket.class)
public final class StreamCodecC2SMixin {
    @ModifyArg(method = "<clinit>", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/network/protocol/common/custom/CustomPacketPayload;codec(Lnet/minecraft/network/protocol/common/custom/CustomPacketPayload$FallbackProvider;Ljava/util/List;)Lnet/minecraft/network/codec/StreamCodec;"),
            index = 1)
    private static List<TypeAndCodec<? super FriendlyByteBuf, ?>> winlandcraft$appendC2S(List<TypeAndCodec<? super FriendlyByteBuf, ?>> vanilla) {
        return StreamNet.c2sTypes(vanilla);
    }
}
