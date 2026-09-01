package dev.winlandcraft;

import java.nio.ByteBuffer;

/** One timestamped encoded access unit; video uses VP9/H.264, audio uses stereo 48 kHz Opus. */
public record StreamMedia(int kind, boolean key, int codec, long timeUs, int width, int height, byte[] data) {
    public static final int VIDEO=0,AUDIO=1,VP9=0,H264=1,OPUS=0,HEADER=24,MAGIC=0x574C5632;
    public StreamMedia(int kind,boolean key,long timeUs,int width,int height,byte[] data){this(kind,key,kind==AUDIO?OPUS:VP9,timeUs,width,height,data);}
    public byte[] pack() {
        var b=ByteBuffer.allocate(HEADER+data.length);
        b.putInt(MAGIC).put((byte)kind).put((byte)(key?1:0)).putShort((short)width).putShort((short)height).putShort((short)codec)
                .putLong(timeUs).putInt(data.length).put(data);
        return b.array();
    }
    public static StreamMedia read(byte[] bytes) {
        var header=header(bytes);if(header==null)return null;
        return new StreamMedia(header.kind,header.key,header.codec,header.timeUs,header.width,header.height,
                java.util.Arrays.copyOfRange(bytes,HEADER,bytes.length));
    }
    /** Validates an envelope without copying its encoded payload. */
    public static Header header(byte[] bytes) {
        if(bytes.length<=HEADER||bytes.length>StreamProtocol.MAX_FRAME_BYTES||intAt(bytes,0)!=MAGIC)return null;
        int kind=bytes[4]&255,flags=bytes[5]&255,w=shortAt(bytes,6),h=shortAt(bytes,8),codec=shortAt(bytes,10),size=intAt(bytes,20);
        long time=longAt(bytes,12);
        if(kind>1||flags>1||time<0||size!=bytes.length-HEADER)return null;
        if(kind==VIDEO&&(codec>H264||w<2||h<2||w>StreamProtocol.MAX_IMAGE_WIDTH||h>StreamProtocol.MAX_IMAGE_HEIGHT))return null;
        if(kind==AUDIO&&(codec!=OPUS||w!=0||h!=0||size>8192))return null;
        return new Header(kind,flags==1,codec,time,w,h,size);
    }
    private static int shortAt(byte[] b,int i){return (b[i]&255)<<8|b[i+1]&255;}
    private static int intAt(byte[] b,int i){return (b[i]&255)<<24|(b[i+1]&255)<<16|(b[i+2]&255)<<8|b[i+3]&255;}
    private static long longAt(byte[] b,int i){return (long)intAt(b,i)<<32|(long)intAt(b,i+4)&0xffffffffL;}
    public record Header(int kind,boolean key,int codec,long timeUs,int width,int height,int size){}
}
