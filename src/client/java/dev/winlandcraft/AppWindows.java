package dev.winlandcraft;

import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import net.minecraft.client.Minecraft;

public final class AppWindows {
    StreamClient streams;
    void stream(AppEntry entry){if(streams!=null&&streams.start(entry.panel()))launcher.close();}
    public final BrowserPanel browser = new BrowserPanel();
    final StreamBrowserPanel streamBrowser = new StreamBrowserPanel();
    public final AppsPanel launcher = new AppsPanel(this);
    public final TasksPanel tasks = new TasksPanel(this);
    public final TaskManagerPanel taskManager = new TaskManagerPanel(this);
    public final FileManagerPanel fileManager = new FileManagerPanel(this);
    final VideoPlayerPanel videoPlayer=new VideoPlayerPanel();
    private final List<VideoPlayerPanel> videoPlayers=new ArrayList<>();
    public final NotepadPanel notepad = new NotepadPanel();
    public final LaserCalibrationPanel laserCalibration=new LaserCalibrationPanel();
    public final List<WorldPanel> windows = new java.util.concurrent.CopyOnWriteArrayList<>(List.of(tasks, launcher, browser, streamBrowser, taskManager, fileManager, notepad, videoPlayer, laserCalibration));
    private final LinkedHashMap<String, AppEntry> custom = new LinkedHashMap<>();
    private final List<NotepadPanel> fileEditors=new ArrayList<>();
    final PluginHost plugins=new PluginHost(this);
    record FileTarget(String id,String name) { }
    List<FileTarget> fileTargets(java.nio.file.Path path){
        var result=new ArrayList<FileTarget>();if(FileAppPlacement.supported(path))result.add(new FileTarget("winlandcraft:notepad","Notepad"));
        if(VideoFileServer.supports(path))result.add(new FileTarget("winlandcraft:video_player","Video Player"));
        result.addAll(plugins.handlers(path));return result;
    }
    boolean openFile(String id,FileManagerPanel source,java.nio.file.Path path,FileAppPlacement.Side side){return id.equals("winlandcraft:video_player")?openVideo(source,path,side):id.equals("winlandcraft:notepad")?openFile(source,path,side):plugins.openFile(id,source,path,side);}
    boolean openFile(FileManagerPanel source,java.nio.file.Path path,FileAppPlacement.Side side) {
        if(!FileAppPlacement.supported(path)||!source.isOpen())return false;
        var editor=fileEditors.stream().filter(p->!p.isOpen()).findFirst().orElse(null);
        if(editor==null) {
            if(fileEditors.size()>=16)return false;
            editor=new NotepadPanel();fileEditors.add(editor);windows.add(editor);
        }
        // Closed editors retain their drafts, just like the main Notepad.
        FileAppPlacement.place(source,editor,side,Minecraft.getInstance().gameRenderer.getMainCamera().getPosition());
        editor.dropFile(path);return true;
    }
    private boolean openVideo(FileManagerPanel source,java.nio.file.Path path,FileAppPlacement.Side side){
        if(!VideoFileServer.supports(path)||!source.isOpen())return false;
        var player=videoPlayers.stream().filter(p->!p.isOpen()).findFirst().orElse(null);
        if(player==null){if(videoPlayers.size()>=16)return false;player=new VideoPlayerPanel();videoPlayers.add(player);windows.add(player);}
        FileAppPlacement.place(source,player,side,Minecraft.getInstance().gameRenderer.getMainCamera().getPosition());player.dropFile(path);return true;
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
        result.add(new AppEntry("Browser(streamable)", streamBrowser, null));
        result.add(new AppEntry("Task Manager", taskManager, null));
        result.add(new AppEntry("File Manager", fileManager, null));
        result.add(new AppEntry("Notepad", notepad, null));
        result.add(new AppEntry("Video Player",videoPlayer,null));
        result.add(new AppEntry("Laser Calibration",laserCalibration,null));
        result.addAll(custom.values());result.addAll(plugins.entries());return result;
    }
    public List<AppEntry> runningApps() {
        var result=new ArrayList<>(appEntries().stream().filter(e -> e.panel.isOpen()).toList());
        for(var editor:fileEditors)if(editor.isOpen())result.add(new AppEntry(editor.windowTitle(),editor,null));
        for(var player:videoPlayers)if(player.isOpen())result.add(new AppEntry(player.windowTitle(),player,null));
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
        else if(entry.panel==streamBrowser) canvas.browserIcon(x,y,size,true);
        else if(entry.panel==fileManager) PixelIcon.FOLDER.draw(canvas,x,y,size,.45f,0xFFFFCE57);
        else if(entry.panel instanceof VideoPlayerPanel){
            canvas.rect(x,y,size,size,.4f,0xFF8C4BC1);
            for(int i=0;i<12;i++)canvas.rect(x+size*.32f+i*size/30f,y+size*.22f+i*size/44f,size/30f,size*.56f-i*size/22f,.45f,-1);
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
    public void openApps() {
        var client = Minecraft.getInstance();
        if (client.level != null) {
            launcher.close();
            launcher.bringToView(client.level, client.gameRenderer.getMainCamera(), 0.3f);
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
