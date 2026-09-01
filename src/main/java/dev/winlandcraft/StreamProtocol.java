package dev.winlandcraft;

import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Bounded H.264/VP9/Opus relay protocol. No client or codec dependencies on the server. */
public final class StreamProtocol {
    public static final int PART_BYTES = 24_000, MAX_FRAME_BYTES = 768_000, MAX_PARTS = 32;
    public static final int MAX_STREAMS = 16, MAX_IMAGE_WIDTH = 1920, MAX_IMAGE_HEIGHT = 1080;
    private static <T extends CustomPacketPayload> CustomPacketPayload.Type<T> type(String name) {
        return new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("winlandcraft", name+"_v5"));
    }
    public record State(UUID owner, UUID session, ResourceLocation dimension,
                        double x, double y, double z, float qx, float qy, float qz, float qw,
                        float width, float height, int pixelsWide, int pixelsHigh, int titlebarPixels, float curve, int facing,
                        boolean remoteControl) implements CustomPacketPayload {
        public static final Type<State> TYPE = StreamProtocol.type("stream_state");
        public static final StreamCodec<RegistryFriendlyByteBuf, State> CODEC = new StreamCodec<>() {
            public State decode(RegistryFriendlyByteBuf b) {
                return new State(b.readUUID(),b.readUUID(),b.readResourceLocation(),b.readDouble(),b.readDouble(),b.readDouble(),
                        b.readFloat(),b.readFloat(),b.readFloat(),b.readFloat(),b.readFloat(),b.readFloat(),b.readVarInt(),b.readVarInt(),b.readVarInt(),b.readFloat(),b.readByte(),b.readBoolean());
            }
            public void encode(RegistryFriendlyByteBuf b, State p) {
                b.writeUUID(p.owner);b.writeUUID(p.session);b.writeResourceLocation(p.dimension);
                b.writeDouble(p.x);b.writeDouble(p.y);b.writeDouble(p.z);b.writeFloat(p.qx);b.writeFloat(p.qy);b.writeFloat(p.qz);b.writeFloat(p.qw);
                b.writeFloat(p.width);b.writeFloat(p.height);b.writeVarInt(p.pixelsWide);b.writeVarInt(p.pixelsHigh);b.writeVarInt(p.titlebarPixels);
                b.writeFloat(p.curve);b.writeByte(p.facing);b.writeBoolean(p.remoteControl);
            }
        };
        public boolean valid() {
            double length=qx*qx+qy*qy+qz*qz+qw*qw;
            return Double.isFinite(x)&&Double.isFinite(y)&&Double.isFinite(z)&&Math.abs(x)<=30_000_000&&Math.abs(y)<=30_000_000&&Math.abs(z)<=30_000_000
                    &&Double.isFinite(length)&&Math.abs(length-1)<.01 && Float.isFinite(width)&&Float.isFinite(height)
                    &&width>=.0001f&&height>=.0001f&&width<=4096&&height<=4096
                    &&pixelsWide>0&&pixelsWide<=1_000_000&&pixelsHigh>0&&pixelsHigh<=1_000_000
                    &&titlebarPixels>=0&&titlebarPixels<=Math.min(256,pixelsHigh)
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
    public record Control(UUID owner,UUID session,UUID controller,int event,int x,int y,int value,int scan,int action,int modifiers,double amount) implements CustomPacketPayload {
        public static final int MOVE=0,MOUSE_DOWN=1,MOUSE_UP=2,SCROLL=3,KEY=4,CHARACTER=5,CANCEL=6,KEEPALIVE=7;
        private static final int SAFE_MODIFIERS=0x31; // Shift, Caps Lock and Num Lock only.
        public static final Type<Control> TYPE=StreamProtocol.type("stream_control");
        public static final StreamCodec<RegistryFriendlyByteBuf,Control> CODEC=new StreamCodec<>() {
            public Control decode(RegistryFriendlyByteBuf b){return new Control(b.readUUID(),b.readUUID(),b.readUUID(),b.readUnsignedByte(),b.readInt(),b.readInt(),b.readInt(),b.readInt(),b.readUnsignedByte(),b.readUnsignedByte(),b.readDouble());}
            public void encode(RegistryFriendlyByteBuf b,Control p){b.writeUUID(p.owner);b.writeUUID(p.session);b.writeUUID(p.controller);b.writeByte(p.event);b.writeInt(p.x);b.writeInt(p.y);b.writeInt(p.value);b.writeInt(p.scan);b.writeByte(p.action);b.writeByte(p.modifiers);b.writeDouble(p.amount);}
        };
        public static Control pointer(UUID owner,UUID session,UUID controller,int event,int x,int y,int button){return new Control(owner,session,controller,event,x,y,button,0,0,0,0);}
        public static Control scroll(UUID owner,UUID session,UUID controller,int x,int y,double amount){return new Control(owner,session,controller,SCROLL,x,y,0,0,0,0,amount);}
        public static Control key(UUID owner,UUID session,UUID controller,int key,int scan,int action,int modifiers){return new Control(owner,session,controller,KEY,0,0,key,scan,action,modifiers,0);}
        public static Control character(UUID owner,UUID session,UUID controller,char character,int modifiers){return new Control(owner,session,controller,CHARACTER,0,0,character,0,0,modifiers,0);}
        public static Control cancel(UUID owner,UUID session,UUID controller){return new Control(owner,session,controller,CANCEL,0,0,0,0,0,0,0);}
        public static Control keepalive(UUID owner,UUID session,UUID controller){return new Control(owner,session,controller,KEEPALIVE,0,0,0,0,0,0,0);}
        public boolean valid(State state) {
            if(!owner.equals(state.owner)||!session.equals(state.session)||(modifiers&~SAFE_MODIFIERS)!=0)return false;
            boolean coordinates=x>=-4096&&x<=state.pixelsWide+4096&&y>=-4096&&y<=state.pixelsHigh+4096;
            return switch(event) {
                case MOVE -> coordinates&&value==0&&scan==0&&action==0&&modifiers==0&&amount==0;
                case MOUSE_DOWN,MOUSE_UP -> coordinates&&value>=0&&value<=7&&scan==0&&action==0&&modifiers==0&&amount==0;
                case SCROLL -> coordinates&&value==0&&scan==0&&action==0&&modifiers==0&&Double.isFinite(amount)&&Math.abs(amount)<=100;
                case KEY -> x==0&&y==0&&value>=-1&&value<=512&&!systemModifierKey(value)
                        &&scan>=-1&&scan<=65535&&action>=0&&action<=2&&amount==0;
                case CHARACTER -> x==0&&y==0&&value>=0&&value<=Character.MAX_VALUE&&scan==0&&action==0&&amount==0;
                case CANCEL,KEEPALIVE -> x==0&&y==0&&value==0&&scan==0&&action==0&&modifiers==0&&amount==0;
                default -> false;
            };
        }
        private static boolean systemModifierKey(int key){return key>=341&&key<=347&&key!=344;}
        @Override public Type<Control> type(){return TYPE;}
    }
    public static java.util.List<Frame> split(UUID owner,UUID session,long sequence,byte[] bytes) {
        if(bytes.length==0||bytes.length>MAX_FRAME_BYTES)throw new IllegalArgumentException("Frame size");
        int count=(bytes.length+PART_BYTES-1)/PART_BYTES;
        var parts=new java.util.ArrayList<Frame>(count);
        for(int i=0;i<count;i++)parts.add(new Frame(owner,session,sequence,i,count,java.util.Arrays.copyOfRange(bytes,i*PART_BYTES,Math.min(bytes.length,(i+1)*PART_BYTES))));
        return parts;
    }
    /** In-order assembly, one bounded frame at a time. Incomplete or replayed frames are discarded. */
    public static final class Assembly {
        private long sequence=-1, started;
        private int next,count,total;
        private byte[][] chunks;
        private long parts,completed,invalid,replayed,abandoned;
        public synchronized byte[] accept(Frame frame,long now) {
            parts++;
            if(!frame.valid()){invalid++;return null;}
            if(frame.part==0) {
                if(frame.sequence<=sequence){replayed++;return null;}
                if(chunks!=null)abandoned++;
                sequence=frame.sequence;started=now;next=0;count=frame.count;total=0;chunks=new byte[count][];
            }
            if(chunks==null||frame.sequence!=sequence||frame.part!=next||frame.count!=count||now-started>2_000) {
                abandoned++;chunks=null;return null;
            }
            chunks[next]=frame.bytes;total+=frame.bytes.length;next++;
            if(next!=count)return null;
            byte[] result=new byte[total];int offset=0;
            for(var chunk:chunks){System.arraycopy(chunk,0,result,offset,chunk.length);offset+=chunk.length;}
            chunks=null;completed++;return result;
        }
        public synchronized Stats stats(){return new Stats(parts,completed,invalid,replayed,abandoned,chunks!=null);}
        public record Stats(long parts,long completed,long invalid,long replayed,long abandoned,boolean assembling){}
    }
}
