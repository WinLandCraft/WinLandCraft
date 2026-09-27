package dev.winlandcraft;
import net.minecraft.client.Minecraft;
import java.util.List;
import java.util.Locale;

/** Native panel: opening Task Manager never creates another Chromium view. */
public final class TaskManagerPanel extends NativePanel {
    private final AppWindows apps;
    private volatile ResourceSampler.Snapshot snapshot;
    private volatile long generation;
    private Thread worker;
    private long nextSample;
    private ResourceSampler sampler;
    private int first,lineCount;
    int visibleRows(){return Math.max(1,(pixelHeight()-100)/26);}
    @Override protected void layoutChanged(){first=Math.clamp(first,0,Math.max(0,lineCount-visibleRows()));}
    public TaskManagerPanel(AppWindows apps) { super(3.2f,1.8f,1280,720); this.apps = apps; }
    @Override public boolean floatingControls(){return true;}
    @Override public String windowTitle(){return "Task Manager";}
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
        if (y >= 0) first = Math.clamp(first - (int) Math.signum(amount), 0, Math.max(0,lineCount-visibleRows()));
    }
    private static String memory(long bytes) { return bytes < 0 ? "Unavailable" : String.format(Locale.ROOT, "%.1f MiB", bytes / 1048576.0); }
    private static String percent(double value) { return Double.isFinite(value) ? String.format(Locale.ROOT, "%.1f%%", value) : "Sampling..."; }
    private static String io(double value) { return Double.isFinite(value) ? memory((long) value) + "/s" : value < 0 ? "Unavailable" : "Sampling..."; }
    @Override void drawSurface(PanelCanvas c) {
        c.rect(-3,-3,pixelWidth()+6,pixelHeight()+6,0,0xFF536579);
        c.rect(0,0,pixelWidth(),pixelHeight(),.1f,0xFF18212D);
        c.text("Chromium resources",24,22,-1,2);
        var lines=new java.util.ArrayList<String>();
        var s=snapshot;
        if(s==null)lines.add("Reading system process counters...");
        else if(!s.error().isEmpty())lines.add(s.error());
        else {
            double cpu=0,read=0,written=0;long ram=0;int threads=0;
            for(var p:s.helpers()){cpu+=p.cpu();ram=ram<0||p.memory()<0?-1:ram+p.memory();threads=threads<0||p.threads()<0?-1:threads+p.threads();read+=p.read();written+=p.written();}
            lines.add("Helper CPU: "+percent(cpu));lines.add("Helper RAM: "+memory(ram));
            lines.add(s.helpers().size()+" processes / "+(threads<0?"threads N/A":threads+" threads"));
            lines.add("Process I/O: read "+io(read)+" | write "+io(written));
            if(s.minecraft()!=null){lines.add("Minecraft + embedded CEF: "+percent(s.minecraft().cpu())+" CPU");lines.add("Minecraft RAM: "+memory(s.minecraft().memory()));}
            lines.add("Java heap: "+memory(s.heapUsed())+" / "+memory(s.heapMax())+" max");
            lines.add("");lines.add("Helper processes (largest working sets)");
            for(var p:s.helpers().stream().limit(5).toList())lines.add(p.pid()+" | "+p.role()+" | CPU "+percent(p.cpu())+" | RAM "+memory(p.memory()));
            if(s.helpers().isEmpty())lines.add("No MCEF helper processes found for this session.");
            int views=0;long texture=0;
            for(var window:apps.windows)if(window instanceof BrowserPanel browser){views+=browser.viewCount();texture+=browser.estimatedTextureBytes();}
            lines.add("");lines.add("Open apps | "+views+" CEF views | RGBA textures: "+memory(texture));
            for(var entry:apps.runningApps())lines.add(entry.name()+" | "+(entry.panel() instanceof BrowserPanel b?b.viewCount()+" views | "+b.pixelWidth()+" x "+b.pixelHeight():"Native panel"));
            lines.add("");lines.add(s.platform()+" | Refresh: 2s | Sample age: "+Math.max(0,(System.currentTimeMillis()-s.sampledAt())/1000)+"s");
            lines.add("CPU is % of all logical CPUs. RAM: summed working sets.");
            lines.add("Shared memory pages may be counted more than once.");
            lines.add("CEF inside Java cannot be separated from Minecraft.");
            lines.add("Process I/O is not network throughput.");
            lines.add("Texture estimate excludes browser buffers/overhead.");
            lines.add("GPU utilization and per-tab CPU/RAM are unavailable.");
        }
        lineCount=lines.size();first=Math.clamp(first,0,Math.max(0,lineCount-visibleRows()));
        for(int i=0;i<visibleRows()&&first+i<lines.size();i++)c.text(Minecraft.getInstance().font.plainSubstrByWidth(lines.get(first+i),Math.max(1,(int)((pixelWidth()-48)/1.5f))),24,62+i*26,0xFFB8CBDE,1.5f);
        c.text("Scroll for more",24,pixelHeight()-26,0xFF9BAABD,1.25f);
    }
}
