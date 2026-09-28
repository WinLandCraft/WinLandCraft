package dev.winlandcraft.mixin;

import dev.winlandcraft.StreamNet;
import java.util.List;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload.TypeAndCodec;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** Extends the vanilla S2C gameplay payload codec with the relay types. Runs on both sides. */
@Mixin(ClientboundCustomPayloadPacket.class)
public final class StreamCodecS2CMixin {
    @ModifyArg(method = "<clinit>", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/network/protocol/common/custom/CustomPacketPayload;codec(Lnet/minecraft/network/protocol/common/custom/CustomPacketPayload$FallbackProvider;Ljava/util/List;)Lnet/minecraft/network/codec/StreamCodec;"),
            index = 1)
    private static List<TypeAndCodec<? super RegistryFriendlyByteBuf, ?>> winlandcraft$appendS2C(List<TypeAndCodec<? super RegistryFriendlyByteBuf, ?>> vanilla) {
        return StreamNet.s2cTypes(vanilla);
    }
}
