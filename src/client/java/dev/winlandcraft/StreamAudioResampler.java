package dev.winlandcraft;

import java.nio.*;
import java.util.function.IntFunction;

/** Stateful stereo linear resampler. The fractional source position survives CEF packet boundaries. */
final class StreamAudioResampler {
    static final int OUTPUT_RATE=48_000;
    record Output(byte[] pcm,int frames){}
    private int inputRate;
    private long inputFrames;
    private double nextInputFrame;
    private float previousLeft,previousRight;
    private boolean hasPrevious;

    void reset(){inputRate=0;inputFrames=0;nextInputFrame=0;previousLeft=previousRight=0;hasPrevious=false;}

    Output process(FloatBuffer left,FloatBuffer right,int frames,int rate,IntFunction<byte[]> allocator) {
        if(frames<=0||rate<=0)return null;
        if(inputRate!=rate){reset();inputRate=rate;}
        long blockStart=inputFrames,blockEnd=blockStart+frames-1;
        double step=rate/(double)OUTPUT_RATE,cursor=nextInputFrame;
        int outputFrames=0;
        while(available(cursor,blockStart,blockEnd)) {outputFrames++;cursor+=step;}
        if(outputFrames==0){remember(left,right,frames);inputFrames+=frames;return null;}
        byte[] pcm=allocator.apply(outputFrames*Float.BYTES*2);
        var bytes=ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN);
        cursor=nextInputFrame;
        for(int frame=0;frame<outputFrames;frame++,cursor+=step) {
            long lower=(long)Math.floor(cursor),upper=(long)Math.ceil(cursor);
            float fraction=(float)(cursor-lower);
            float la=sample(left,blockStart,lower,previousLeft),lb=sample(left,blockStart,upper,previousLeft);
            float ra=sample(right,blockStart,lower,previousRight),rb=sample(right,blockStart,upper,previousRight);
            float l=la+(lb-la)*fraction,r=ra+(rb-ra)*fraction;
            bytes.putFloat(frame*Float.BYTES,finite(l));
            bytes.putFloat((outputFrames+frame)*Float.BYTES,finite(r));
        }
        nextInputFrame=cursor;remember(left,right,frames);inputFrames+=frames;
        return new Output(pcm,outputFrames);
    }

    private boolean available(double position,long blockStart,long blockEnd) {
        long lower=(long)Math.floor(position),upper=(long)Math.ceil(position);
        return upper<=blockEnd&&lower>=blockStart-(hasPrevious?1:0);
    }
    private static float sample(FloatBuffer samples,long blockStart,long position,float previous) {
        return position<blockStart?previous:samples.get(Math.toIntExact(position-blockStart));
    }
    private void remember(FloatBuffer left,FloatBuffer right,int frames) {
        previousLeft=left.get(frames-1);previousRight=right.get(frames-1);hasPrevious=true;
    }
    private static float finite(float sample){return Float.isFinite(sample)?Math.clamp(sample,-1,1):0;}
}
