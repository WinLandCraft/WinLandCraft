package dev.winlandcraft;

import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.nio.file.Path;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;

public final class AppWindows {
    private static final int MAX_FILE_WINDOWS_PER_APP=16;
    StreamClient streams;
    void stream(AppEntry entry){if(streams!=null&&streams.start(entry.panel()))launcher.close();}
    public final BrowserPanel browser = new BrowserPanel();
    public final AppsPanel launcher = new AppsPanel(this);
    public final TasksPanel tasks = new TasksPanel(this);
    public final TaskManagerPanel taskManager = new TaskManagerPanel(this);
    public final FileManagerPanel fileManager = new FileManagerPanel(this);
    final ImageViewerPanel imageViewer=new ImageViewerPanel();
    private final List<ImageViewerPanel> imageViewers=new ArrayList<>();
    public final NotepadPanel notepad = new NotepadPanel();
    public final LaserCalibrationPanel laserCalibration=new LaserCalibrationPanel();
    public final List<WorldPanel> windows = new java.util.concurrent.CopyOnWriteArrayList<>(List.of(tasks, launcher, browser, taskManager, fileManager, notepad, imageViewer, laserCalibration));
    private final LinkedHashMap<String, AppEntry> custom = new LinkedHashMap<>();
    private final List<NotepadPanel> fileEditors=new ArrayList<>();
    final PluginHost plugins=new PluginHost(this);
    record FileTarget(String id,String name) { }
    List<FileTarget> fileTargets(Path path){
        var result=new ArrayList<FileTarget>();if(FileAppPlacement.supported(path))result.add(new FileTarget("winlandcraft:notepad","Notepad"));
        if(ImageViewerPage.supports(path))result.addFirst(new FileTarget("winlandcraft:image_viewer","Image Viewer"));
        result.addAll(plugins.handlers(path));return result;
    }
    boolean openFile(String id,FileManagerPanel source,Path path,FileAppPlacement.Side side) {
        if(!source.isOpen())return false;
        WorldPanel panel;
        switch(id) {
            case "winlandcraft:image_viewer"->panel=ImageViewerPage.supports(path)?fileWindow(imageViewers,ImageViewerPanel::new):null;
            case "winlandcraft:notepad"->panel=FileAppPlacement.supported(path)?fileWindow(fileEditors,NotepadPanel::new):null;
            default->{return plugins.openFile(id,source,path,side);}
        }
        if(panel==null)return false;
        FileAppPlacement.place(source,panel,side,Minecraft.getInstance().gameRenderer.getMainCamera().getPosition());
        panel.dropFile(path);return true;
    }
    <T extends WorldPanel> T fileWindow(List<T> pool,Supplier<T> factory) {
        // Reuse closed editors without discarding their drafts.
        for(var panel:pool)if(!panel.isOpen())return panel;
        if(pool.size()>=MAX_FILE_WINDOWS_PER_APP)return null;
        T panel=factory.get();pool.add(panel);windows.add(panel);return panel;
    }
    public record AppEntry(String name, WorldPanel panel, WebApps.App definition) { }
    public AppWindows() { reconcileWebApps(); }
    public void reconcileWebApps() {
        var iterator = custom.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next(); var updated = WebApps.find(entry.getKey());
            if (updated == null || !updated.url().equals(entry.getValue().definition.url())) {
                entry.getValue().panel.close(); windows.remove(entry.getValue().panel); iterator.remove();
            }
        }
        for (var definition : WebApps.all()) {
            var previous = custom.get(definition.id());
            var panel = previous == null ? new BrowserPanel(definition.url()) : (BrowserPanel) previous.panel;
            panel.setAppName(definition.name());
            if (previous == null) windows.add(panel);
            custom.put(definition.id(), new AppEntry(definition.name(), panel, definition));
        }
    }
    public List<AppEntry> appEntries() {
        var result = new ArrayList<AppEntry>();
        result.add(new AppEntry("Browser", browser, null));
        result.add(new AppEntry("Task Manager", taskManager, null));
        result.add(new AppEntry("File Manager", fileManager, null));
        result.add(new AppEntry("Notepad", notepad, null));
        result.add(new AppEntry("Image Viewer",imageViewer,null));
        result.add(new AppEntry("Laser Calibration",laserCalibration,null));
        result.addAll(custom.values());result.addAll(plugins.entries());return result;
    }
    public List<AppEntry> runningApps() {
        var result=new ArrayList<>(appEntries().stream().filter(e -> e.panel.isOpen()).toList());
        for(var editor:fileEditors)if(editor.isOpen())result.add(new AppEntry(editor.windowTitle(),editor,null));
        for(var viewer:imageViewers)if(viewer.isOpen())result.add(new AppEntry(viewer.windowTitle(),viewer,null));
        result.addAll(plugins.running());return result;
    }
    public void launch(AppEntry entry) {
        var client = Minecraft.getInstance();
        if (client.level != null) { entry.panel.open(client); launcher.close(); }
    }
    public void drawIcon(PanelCanvas canvas, AppEntry entry, int x, int y, int size) {
        var icon = WebApps.icon(entry.definition);
        if(entry.panel instanceof PluginPanel plugin){
            if(plugin.definition.icon()!=null)canvas.texture(net.minecraft.resources.ResourceLocation.parse(plugin.definition.icon()),x,y,size,size,.4f);
            else PixelIcon.APPS.draw(canvas,x,y,size,.45f,0xFF8BD9CE);
        }
        else if (icon != null) canvas.texture(icon, x, y, size, size, 0.4f);
        else if(entry.panel==fileManager) PixelIcon.FOLDER.draw(canvas,x,y,size,.45f,0xFFFFCE57);
        else if(entry.panel instanceof ImageViewerPanel){
            canvas.rect(x,y,size,size,.4f,0xFF286E70);canvas.rect(x+size*.65f,y+size*.2f,size*.16f,size*.16f,.45f,0xFFFFDC83);
            for(int i=0;i<12;i++)canvas.rect(x+size*.12f+i*size*.06f,y+size*(.72f-Math.min(i,12-i)*.045f),size*.065f,size*(.12f+Math.min(i,12-i)*.045f),.45f,0xFFB0EBCE);
        }
        else if(entry.panel instanceof NotepadPanel)PixelIcon.FILE_TEXT.draw(canvas,x,y,size,.45f,0xFF69D1E9);
        else if(entry.panel==taskManager)PixelIcon.CHART.draw(canvas,x,y,size,.45f,0xFF65E4AE);
        else if(entry.panel==laserCalibration)PixelIcon.SCALE.draw(canvas,x,y,size,.45f,0xFFFFC857);
        else if (entry.definition == null) canvas.browserIcon(x, y, size);
        else {
            canvas.rect(x, y, size, size, 0.4f, 0xFF426E80);
            PixelIcon.EXTERNAL_LINK.draw(canvas,x,y,size,.45f,-1);
        }
    }
    public void toggleStartMenu() {
        if (launcher.isAttached()) launcher.close();
        else launcher.openAttached();
    }
    public void syncAttachments() { launcher.syncAttachment(); }
    public void openBrowser() {
        var client = Minecraft.getInstance();
        if (client.level != null) {
            browser.open(client);
            launcher.close();
        }
    }
}
