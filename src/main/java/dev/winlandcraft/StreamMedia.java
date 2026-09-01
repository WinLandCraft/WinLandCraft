package dev.winlandcraft;

import java.nio.ByteBuffer;

/** One timestamped encoded access unit; video uses VP9, audio uses stereo 48 kHz Opus. */
public record StreamMedia(int kind, boolean key, long timeUs, int width, int height, byte[] data) {
    public static final int VIDEO=0,AUDIO=1,HEADER=24,MAGIC=0x574C5632;
    public byte[] pack() {
        var b=ByteBuffer.allocate(HEADER+data.length);
        b.putInt(MAGIC).put((byte)kind).put((byte)(key?1:0)).putShort((short)width).putShort((short)height).putShort((short)0)
                .putLong(timeUs).putInt(data.length).put(data);
        return b.array();
    }
    public static StreamMedia read(byte[] bytes) {
        if(bytes.length<=HEADER||bytes.length>StreamProtocol.MAX_FRAME_BYTES)return null;
        var b=ByteBuffer.wrap(bytes);
        if(b.getInt()!=MAGIC)return null;
        int kind=b.get()&255,flags=b.get()&255,w=b.getShort()&65535,h=b.getShort()&65535;b.getShort();
        long time=b.getLong();int size=b.getInt();
        if(kind>1||flags>1||time<0||size!=bytes.length-HEADER)return null;
        if(kind==VIDEO&&(w<2||h<2||w>StreamProtocol.MAX_IMAGE_WIDTH||h>StreamProtocol.MAX_IMAGE_HEIGHT))return null;
        if(kind==AUDIO&&(w!=0||h!=0||size>8192))return null;
        byte[] data=new byte[size];b.get(data);return new StreamMedia(kind,flags==1,time,w,h,data);
    }
}
