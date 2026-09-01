package dev.winlandcraft;

import com.sun.jna.Library;
import com.sun.jna.Native;
import java.nio.file.*;

/** Linux /proc reader; needs no external commands or optional native packages. */
final class LinuxResourceBackend implements ResourceSampler.Backend {
    private interface LibC extends Library {LibC INSTANCE=Native.load("c",LibC.class);long sysconf(int name);}
    private static final class Config {
        static final long TICKS=Math.max(1,LibC.INSTANCE.sysconf(2));
        static final long PAGE_SIZE=Math.max(1,LibC.INSTANCE.sysconf(30));
    }
    @Override public ResourceSampler.Raw read(ProcessHandle process) {
        int pid=(int)process.pid();Path directory=Path.of("/proc",Integer.toString(pid));
        try {
            String stat=Files.readString(directory.resolve("stat"));var io=java.util.List.<String>of();
            try {
                io=Files.readAllLines(directory.resolve("io"));
            } catch(java.io.IOException ignored){}
            return parse(pid,stat,io,Config.TICKS,Config.PAGE_SIZE);
        } catch(Exception | LinkageError unavailable){return new PortableResourceBackend().read(process);}
    }
    static ResourceSampler.Raw parse(int pid,String stat,java.util.List<String> io,long ticks,long pageSize) {
        int close=stat.lastIndexOf(')');if(close<0)throw new IllegalArgumentException("Malformed /proc stat");
        String[] fields=stat.substring(close+2).trim().split("\\s+");
        long cpuTicks=Long.parseLong(fields[11])+Long.parseLong(fields[12]);
        int threads=Integer.parseInt(fields[17]);long started=Long.parseLong(fields[19]);
        long memory=Long.parseLong(fields[21])*pageSize,read=-1,written=-1;
        for(String line:io) {
            if(line.startsWith("read_bytes:"))read=Long.parseLong(line.substring(11).trim());
            else if(line.startsWith("write_bytes:"))written=Long.parseLong(line.substring(12).trim());
        }
        return new ResourceSampler.Raw(pid,started,cpuTicks*1000/Math.max(1,ticks),memory,threads,read,written);
    }
    @Override public String name(){return "Linux /proc";}
}
