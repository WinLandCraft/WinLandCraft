package dev.winlandcraft;

public final class ResourceChecks {
    public static void main(String[] args) {
        try { FileDirectoryChecks.run(); } catch(Exception error){throw new AssertionError(error);}
        try { NoteChecks.run(); } catch(Exception error){throw new AssertionError(error);}
        near(12.5, ResourceSampler.cpuPercent(1000, 0, 1_000_000_000L, 8), "one busy core on eight CPUs");
        near(100, ResourceSampler.cpuPercent(8000, 0, 1_000_000_000L, 8), "all cores busy");
        near(512, ResourceSampler.rate(2024, 1000, 2_000_000_000L), "I/O rate");
        if (!Double.isNaN(ResourceSampler.rate(1, 2, 100))) throw new AssertionError("counter reset");
        if (!Double.isNaN(ResourceSampler.rate(1, 0, 0))) throw new AssertionError("zero elapsed time");
        if (!ResourceSampler.chromiumHelper("jcef_helper.exe") || ResourceSampler.chromiumHelper("chrome.exe")
                || ResourceSampler.chromiumHelper("javaw.exe")) throw new AssertionError("helper isolation");
        if (!(ResourceSampler.backend("Plan 9") instanceof PortableResourceBackend)) throw new AssertionError("portable fallback");
        if (!(ResourceSampler.backend("Darwin") instanceof PortableResourceBackend)) throw new AssertionError("macOS must not select Windows");
        if (!(ResourceSampler.backend("Windows 11") instanceof WindowsResourceBackend)) throw new AssertionError("Windows backend selection");
        if (!(ResourceSampler.backend("GNU/Linux") instanceof LinuxResourceBackend)) throw new AssertionError("Linux backend selection");
        String stat="42 (jcef helper with spaces) S 1 2 3 4 5 6 7 8 9 10 120 30 0 0 0 0 17 0 9000 0 50";
        var linux=LinuxResourceBackend.parse(42,stat,java.util.List.of("read_bytes: 4096","write_bytes: 8192"),100,4096);
        if(linux.cpuMillis()!=1500||linux.threads()!=17||linux.started()!=9000||linux.memory()!=204800
                ||linux.read()!=4096||linux.written()!=8192) throw new AssertionError("Linux /proc parsing: "+linux);
        System.out.println("Resource counters: CPU normalization, I/O rates, resets, and helper isolation passed.");
        if (args.length > 0 && args[0].equals("--live")) {
            var sampler = new ResourceSampler();
            var first = sampler.sample(); var second = sampler.sample();
            if (first.minecraft() == null || first.minecraft().memory() <= 0 || !Double.isFinite(second.minecraft().cpu()))
                throw new AssertionError("Live OS counters unavailable");
            System.out.println("Live OS sampling passed: current JVM working set " + second.minecraft().memory() + " bytes; "
                    + second.minecraft().threads() + " threads. Host browser processes are excluded.");
        }
    }
    private static void near(double expected, double actual, String label) {
        if (!Double.isFinite(actual) || Math.abs(expected - actual) > 0.00001) throw new AssertionError(label + ": " + actual);
    }
}
