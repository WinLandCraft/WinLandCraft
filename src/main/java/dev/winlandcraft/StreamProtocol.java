package dev.winlandcraft;

import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Bounded VP9/Opus relay protocol. No client or codec dependencies on the server. */
public final class StreamProtocol {
    public static final int PART_BYTES = 24_000, MAX_FRAME_BYTES = 768_000, MAX_PARTS = 32;
    public static final int MAX_STREAMS = 16, MAX_IMAGE_WIDTH = 1920, MAX_IMAGE_HEIGHT = 1080;
    private static <T extends CustomPacketPayload> CustomPacketPayload.Type<T> type(String name) {
        return new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("winlandcraft", name+"_v3"));
    }
    public record State(UUID owner, UUID session, ResourceLocation dimension,
                        double x, double y, double z, float qx, float qy, float qz, float qw,
                        float width, float height, int pixelsWide, int pixelsHigh, float curve, int facing) implements CustomPacketPayload {
        public static final Type<State> TYPE = StreamProtocol.type("stream_state");
        public static final StreamCodec<RegistryFriendlyByteBuf, State> CODEC = new StreamCodec<>() {
            public State decode(RegistryFriendlyByteBuf b) {
                return new State(b.readUUID(),b.readUUID(),b.readResourceLocation(),b.readDouble(),b.readDouble(),b.readDouble(),
                        b.readFloat(),b.readFloat(),b.readFloat(),b.readFloat(),b.readFloat(),b.readFloat(),b.readVarInt(),b.readVarInt(),b.readFloat(),b.readByte());
            }
            public void encode(RegistryFriendlyByteBuf b, State p) {
                b.writeUUID(p.owner);b.writeUUID(p.session);b.writeResourceLocation(p.dimension);
                b.writeDouble(p.x);b.writeDouble(p.y);b.writeDouble(p.z);b.writeFloat(p.qx);b.writeFloat(p.qy);b.writeFloat(p.qz);b.writeFloat(p.qw);
                b.writeFloat(p.width);b.writeFloat(p.height);b.writeVarInt(p.pixelsWide);b.writeVarInt(p.pixelsHigh);b.writeFloat(p.curve);b.writeByte(p.facing);
            }
        };
        public boolean valid() {
            double length=qx*qx+qy*qy+qz*qz+qw*qw;
            return Double.isFinite(x)&&Double.isFinite(y)&&Double.isFinite(z)&&Math.abs(x)<=30_000_000&&Math.abs(y)<=30_000_000&&Math.abs(z)<=30_000_000
                    &&Double.isFinite(length)&&Math.abs(length-1)<.01 && Float.isFinite(width)&&Float.isFinite(height)
                    &&width>=.0001f&&height>=.0001f&&width<=4096&&height<=4096
                    &&pixelsWide>0&&pixelsWide<=1_000_000&&pixelsHigh>0&&pixelsHigh<=1_000_000
                    &&Float.isFinite(curve)&&curve>=0&&curve<=1&&(facing==1||facing==-1);
        }
        @Override public Type<State> type(){return TYPE;}
    }
    public record Frame(UUID owner, UUID session, long sequence, int part, int count, byte[] bytes) implements CustomPacketPayload {
        public static final Type<Frame> TYPE = StreamProtocol.type("stream_frame");
        public static final StreamCodec<RegistryFriendlyByteBuf, Frame> CODEC = new StreamCodec<>() {
            public Frame decode(RegistryFriendlyByteBuf b) {return new Frame(b.readUUID(),b.readUUID(),b.readLong(),b.readVarInt(),b.readVarInt(),b.readByteArray(PART_BYTES));}
            public void encode(RegistryFriendlyByteBuf b,Frame p) {b.writeUUID(p.owner);b.writeUUID(p.session);b.writeLong(p.sequence);b.writeVarInt(p.part);b.writeVarInt(p.count);b.writeByteArray(p.bytes);}
        };
        public boolean valid(){return sequence>=0&&count>0&&count<=MAX_PARTS&&part>=0&&part<count&&bytes.length>0&&bytes.length<=PART_BYTES&&(part==count-1||bytes.length==PART_BYTES);}
        @Override public Type<Frame> type(){return TYPE;}
    }
    public record Stop(UUID owner, UUID session) implements CustomPacketPayload {
        public static final Type<Stop> TYPE = StreamProtocol.type("stream_stop");
        public static final StreamCodec<RegistryFriendlyByteBuf, Stop> CODEC = new StreamCodec<>() {
            public Stop decode(RegistryFriendlyByteBuf b){return new Stop(b.readUUID(),b.readUUID());}
            public void encode(RegistryFriendlyByteBuf b,Stop p){b.writeUUID(p.owner);b.writeUUID(p.session);}
        };
        @Override public Type<Stop> type(){return TYPE;}
    }
    public static java.util.List<Frame> split(UUID owner,UUID session,long sequence,byte[] bytes) {
        if(bytes.length==0||bytes.length>MAX_FRAME_BYTES)throw new IllegalArgumentException("Frame size");
        int count=(bytes.length+PART_BYTES-1)/PART_BYTES;
        var parts=new java.util.ArrayList<Frame>();
        for(int i=0;i<count;i++)parts.add(new Frame(owner,session,sequence,i,count,java.util.Arrays.copyOfRange(bytes,i*PART_BYTES,Math.min(bytes.length,(i+1)*PART_BYTES))));
        return parts;
    }
    /** In-order assembly, one bounded frame at a time. Incomplete or replayed frames are discarded. */
    public static final class Assembly {
        private long sequence=-1, started;
        private int next,count;
        private java.io.ByteArrayOutputStream data;
        public byte[] accept(Frame frame,long now) {
            if(!frame.valid())return null;
            if(frame.part==0) {
                if(frame.sequence<=sequence)return null;
                sequence=frame.sequence;started=now;next=0;count=frame.count;data=new java.io.ByteArrayOutputStream();
            }
            if(data==null||frame.sequence!=sequence||frame.part!=next||frame.count!=count||now-started>2_000) {data=null;return null;}
            data.writeBytes(frame.bytes);next++;
            if(next!=count)return null;
            byte[] result=data.toByteArray();data=null;return result;
        }
    }
}
