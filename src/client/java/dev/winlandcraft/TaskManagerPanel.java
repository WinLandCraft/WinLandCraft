package dev.winlandcraft;

import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.Minecraft;
import java.util.List;
import java.util.Locale;

/** Native panel: opening Task Manager never creates another Chromium view. */
public final class TaskManagerPanel extends WorldPanel {
    private final AppWindows apps;
    private volatile ResourceSampler.Snapshot snapshot;
    private volatile long generation;
    private Thread worker;
    private long nextSample;
    private ResourceSampler sampler;
    private int first;
    public TaskManagerPanel(AppWindows apps) { super(3.2f, 1.8f); this.apps = apps; }
    @Override public int pixelWidth() { return 1280; }
    @Override public int pixelHeight() { return 720; }
    @Override public int titlebarHeight() { return 32; }
    @Override protected float minimumWidth() { return 1.6f; }
    @Override protected float minimumHeight() { return 0.9f; }
    @Override public void close() {
        super.close(); generation++; snapshot = null; nextSample = 0;
        if (worker != null) worker.interrupt();
    }
    @Override public void tick(Minecraft client) {
        super.tick(client);
        if (!isOpen() || System.nanoTime() < nextSample || worker != null && worker.isAlive()) return;
        nextSample = System.nanoTime() + 2_000_000_000L;
        long request = generation;
        boolean fresh = snapshot == null;
        worker = Thread.startVirtualThread(() -> {
            try {
                if (fresh || sampler == null) sampler = new ResourceSampler();
                var result = sampler.sample();
                if (generation == request) snapshot = result;
            } catch (Exception | LinkageError error) {
                if (generation == request) snapshot = new ResourceSampler.Snapshot(List.of(), null, 0, 0,
                        System.currentTimeMillis(), "Process metrics unavailable. See latest.log.", "Unavailable");
                WinLandCraftClient.LOGGER.debug("Task Manager sampling failed", error);
            }
        });
    }
    @Override public void scroll(int x, int y, double amount) {
        if (y >= 0) first = Math.clamp(first - (int) Math.signum(amount), 0, Math.max(0, apps.runningApps().size() - 4));
    }
    private static String memory(long bytes) { return bytes < 0 ? "Unavailable" : String.format(Locale.ROOT, "%.1f MiB", bytes / 1048576.0); }
    private static String percent(double value) { return Double.isFinite(value) ? String.format(Locale.ROOT, "%.1f%%", value) : "Sampling..."; }
    private static String io(double value) { return Double.isFinite(value) ? memory((long) value) + "/s" : value < 0 ? "Unavailable" : "Sampling..."; }
    private static String fit(String text, int pixels) { return Minecraft.getInstance().font.plainSubstrByWidth(text, pixels / 2); }
    @Override public void render(WorldRenderContext context) {
        try (var c = surfaceCanvas(context)) {
            if (c == null || !c.frontFacing()) return;
            c.rect(-3, -35, 1286, 758, 0, 0xFF536579);
            c.rect(0, -32, 1280, 32, 0.3f, 0xFF285947);
            c.text("Task Manager", 12, -22, -1, 1.5f);
            renderUngroup(c);
            renderClose(c);
            c.rect(0, 0, 1280, 720, 0.1f, 0xFF18212D);
            c.text("Chromium resources", 24, 22, -1, 2);
            var s = snapshot;
            if (s == null) { c.text("Reading system process counters...", 24, 64, 0xFFB8CBDE, 2); return; }
            if (!s.error().isEmpty()) { c.text(s.error(), 24, 64, 0xFFFFA5A5, 2); return; }
            double cpu = 0, read = 0, written = 0; long ram = 0; int threads = 0;
            for (var p : s.helpers()) { cpu += p.cpu(); ram = ram < 0 || p.memory() < 0 ? -1 : ram + p.memory(); threads = threads < 0 || p.threads() < 0 ? -1 : threads + p.threads(); read += p.read(); written += p.written(); }
            c.text("Helper CPU: " + percent(cpu), 24, 66, 0xFF65E4AE, 2);
            c.text("Helper RAM: " + memory(ram), 430, 66, 0xFF65E4AE, 2);
            c.text(s.helpers().size() + " processes / " + (threads < 0 ? "threads N/A" : threads + " threads"), 850, 66, 0xFF65E4AE, 2);
            c.text("Process I/O: read " + io(read) + " | write " + io(written), 24, 98, 0xFFB8CBDE, 1.5f);
            if (s.minecraft() != null) c.text("Minecraft + embedded CEF: " + percent(s.minecraft().cpu()) + " CPU | " + memory(s.minecraft().memory()) + " RAM", 24, 132, -1, 2);
            c.text("Java heap: " + memory(s.heapUsed()) + " / " + memory(s.heapMax()) + " max", 24, 164, 0xFFB8CBDE, 1.5f);
            c.text("Helper processes (largest working sets)", 24, 208, -1, 2);
            c.text("PID       Role                       CPU          RAM", 24, 240, 0xFF9BAABD, 1.5f);
            for (int i = 0; i < Math.min(5, s.helpers().size()); i++) {
                var p = s.helpers().get(i); int y = 268 + i * 25;
                c.text(Integer.toString(p.pid()), 24, y, -1, 1.5f); c.text(p.role(), 140, y, -1, 1.5f);
                c.text(percent(p.cpu()), 440, y, -1, 1.5f); c.text(memory(p.memory()), 610, y, -1, 1.5f);
            }
            if (s.helpers().isEmpty()) c.text("No MCEF helper processes found for this Minecraft session.", 24, 270, 0xFFB8CBDE, 1.5f);
            int views = 0; long texture = 0;
            for (var window : apps.windows) if (window instanceof BrowserPanel browser) { views += browser.viewCount(); texture += browser.estimatedTextureBytes(); }
            c.text("Open apps | " + views + " CEF views | estimated RGBA textures: " + memory(texture), 24, 412, -1, 1.5f);
            var running = apps.runningApps(); first = Math.clamp(first, 0, Math.max(0, running.size() - 4));
            for (int i = 0; i < 4 && first + i < running.size(); i++) {
                var entry = running.get(first + i); int y = 447 + i * 32;
                c.text(fit(entry.name(), 520), 24, y, -1, 2);
                String info = entry.panel() instanceof BrowserPanel b ? b.viewCount() + " views | " + b.pixelWidth() + " x " + b.pixelHeight() : "Native panel";
                c.text(info, 580, y, 0xFFB8CBDE, 1.5f);
            }
            c.text("Scroll for more apps. " + s.platform() + " | Refresh: 2s | Sample age: " + Math.max(0, (System.currentTimeMillis() - s.sampledAt()) / 1000) + "s", 24, 592, 0xFF9BAABD, 1.5f);
            c.text("CPU is % of total available logical CPUs. RAM = summed working sets; shared pages can repeat.", 24, 626, 0xFF9BAABD, 1.2f);
            c.text("CEF inside Java cannot be separated from Minecraft. Process I/O is not network throughput.", 24, 649, 0xFF9BAABD, 1.2f);
            c.text("Texture estimate excludes browser buffers/overhead. GPU utilization and per-tab CPU/RAM unavailable.", 24, 672, 0xFF9BAABD, 1.2f);
        }
    }
}
