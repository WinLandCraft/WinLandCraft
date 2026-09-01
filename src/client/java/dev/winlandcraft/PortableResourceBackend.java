package dev.winlandcraft;

/** ProcessHandle fallback for macOS and other JVM platforms; some fields are unavailable. */
class PortableResourceBackend implements ResourceSampler.Backend {
    @Override public ResourceSampler.Raw read(ProcessHandle process) {
        var info=process.info();var cpu=info.totalCpuDuration();if(cpu.isEmpty())return null;
        long started=info.startInstant().map(java.time.Instant::toEpochMilli).orElse(0L);
        return new ResourceSampler.Raw((int)process.pid(),started,cpu.get().toMillis(),-1,-1,-1,-1);
    }
    @Override public String name(){return "Portable process metrics";}
}
