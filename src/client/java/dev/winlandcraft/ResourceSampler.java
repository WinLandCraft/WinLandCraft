package dev.winlandcraft;

import java.util.*;

/** Cross-platform process aggregation. Platform-native classes are selected lazily. */
final class ResourceSampler {
    record Row(int pid, String role, double cpu, long memory, int threads, double read, double written) { }
    record Snapshot(List<Row> helpers, Row minecraft, long heapUsed, long heapMax, long sampledAt, String error, String platform) { }
    record Counter(long started, long cpu, long read, long written, long nanos) { }
    record Raw(int pid, long started, long cpuMillis, long memory, int threads, long read, long written) { }
    interface Backend { Raw read(ProcessHandle process); String name(); }
    private final Backend backend=backend(System.getProperty("os.name",""));
    private Map<Integer,Counter> previous=Map.of();
    private final int processors=Math.max(1,Runtime.getRuntime().availableProcessors());

    static Backend backend(String os) {
        String name=os.toLowerCase(Locale.ROOT);
        if(name.startsWith("windows")) return new WindowsResourceBackend();
        if(name.contains("linux")) return new LinuxResourceBackend();
        return new PortableResourceBackend();
    }
    static boolean chromiumHelper(String name) {
        String lower=name.toLowerCase(Locale.ROOT);
        lower=lower.substring(Math.max(lower.lastIndexOf('/'),lower.lastIndexOf('\\'))+1);
        return lower.contains("jcef")||lower.contains("mcef")||lower.startsWith("cef_helper");
    }
    static double rate(long current,long previous,long nanos) {
        return nanos<=0||current<0||previous<0||current<previous?Double.NaN:(current-previous)*1_000_000_000.0/nanos;
    }
    static double cpuPercent(long currentMillis,long previousMillis,long nanos,int processors) {
        double value=rate(currentMillis,previousMillis,nanos)/(10*Math.max(1,processors));
        return Double.isNaN(value)?value:Math.clamp(value,0,100);
    }
    Snapshot sample() {
        var processes=ProcessHandle.current().descendants().filter(p->chromiumHelper(p.info().command().orElse(""))).toList();
        Map<Integer,Counter> next=new HashMap<>();List<Row> rows=new ArrayList<>();long now=System.nanoTime();
        for(var process:processes){var row=row(process,role(process),now,next);if(row!=null)rows.add(row);}
        rows.sort(Comparator.comparingLong(Row::memory).reversed());
        Row minecraft=row(ProcessHandle.current(),"Minecraft + embedded CEF",now,next);
        previous=next;var runtime=Runtime.getRuntime();
        return new Snapshot(List.copyOf(rows),minecraft,runtime.totalMemory()-runtime.freeMemory(),runtime.maxMemory(),
                System.currentTimeMillis(),"",backend.name());
    }
    private Row row(ProcessHandle process,String role,long now,Map<Integer,Counter> next) {
        Raw raw=backend.read(process);if(raw==null)return null;
        Counter old=previous.get(raw.pid);boolean valid=old!=null&&old.started==raw.started;
        double cpu=valid?cpuPercent(raw.cpuMillis,old.cpu,now-old.nanos,processors):Double.NaN;
        double read=raw.read<0?Double.NEGATIVE_INFINITY:valid?rate(raw.read,old.read,now-old.nanos):Double.NaN;
        double written=raw.written<0?Double.NEGATIVE_INFINITY:valid?rate(raw.written,old.written,now-old.nanos):Double.NaN;
        next.put(raw.pid,new Counter(raw.started,raw.cpuMillis,raw.read,raw.written,now));
        return new Row(raw.pid,role,cpu,raw.memory,raw.threads,read,written);
    }
    private static String role(ProcessHandle process) {
        String command=process.info().commandLine().orElse("");
        if(command.contains("--type=renderer"))return "Renderer";
        if(command.contains("--type=gpu-process"))return "GPU process";
        if(command.contains("--type=utility"))return "Utility";
        return "CEF helper";
    }
}
