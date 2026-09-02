package dev.winlandcraft;

record StreamQuality(int fps,int kbps,int height,boolean audio,int audioKbps) {
    static final String[] CODECS={"Auto","H.264 / HW","VP9 / HW","VP9 / SW"};
    static final int[] FPS={15,24,30,60},BITRATES={500,1000,2000,4000,6000,8000},HEIGHTS={360,480,720,1080},AUDIO={64,96,128,192};
    static StreamQuality current(){return new StreamQuality(ModSettings.streamFps,ModSettings.streamKbps,ModSettings.streamHeight,ModSettings.streamAudio,ModSettings.streamAudioKbps);}
    static int nearest(int value,int[] options){int best=options[0];for(int option:options)if(Math.abs((long)option-value)<Math.abs((long)best-value))best=option;return best;}
    static int step(int value,int[] options,int delta){for(int i=0;i<options.length;i++)if(options[i]==value)return options[Math.clamp(i+delta,0,options.length-1)];return options[0];}
}
