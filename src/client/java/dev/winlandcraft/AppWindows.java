package dev.winlandcraft;

import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import net.minecraft.client.Minecraft;

public final class AppWindows {
    public final BrowserPanel browser = new BrowserPanel();
    final StreamBrowserPanel streamBrowser = new StreamBrowserPanel();
    public final AppsPanel launcher = new AppsPanel(this);
    public final TasksPanel tasks = new TasksPanel(this);
    public final TaskManagerPanel taskManager = new TaskManagerPanel(this);
    public final FileManagerPanel fileManager = new FileManagerPanel();
    public final NotepadPanel notepad = new NotepadPanel();
    public final LaserCalibrationPanel laserCalibration=new LaserCalibrationPanel();
    public final List<WorldPanel> windows = new java.util.concurrent.CopyOnWriteArrayList<>(List.of(tasks, launcher, browser, streamBrowser, taskManager, fileManager, notepad, laserCalibration));
    private final LinkedHashMap<String, AppEntry> custom = new LinkedHashMap<>();
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
        result.add(new AppEntry("Laser Calibration",laserCalibration,null));
        result.addAll(custom.values()); return result;
    }
    public List<AppEntry> runningApps() { return appEntries().stream().filter(e -> e.panel.isOpen()).toList(); }
    public void launch(AppEntry entry) {
        var client = Minecraft.getInstance();
        if (client.level != null) { entry.panel.open(client); launcher.close(); }
    }
    public void drawIcon(PanelCanvas canvas, AppEntry entry, int x, int y, int size) {
        var icon = WebApps.icon(entry.definition);
        if (icon != null) canvas.texture(icon, x, y, size, size, 0.4f);
        else if(entry.panel==streamBrowser) canvas.browserIcon(x,y,size,true);
        else if(entry.panel==fileManager) PixelIcon.FOLDER.draw(canvas,x,y,size,.45f,0xFFFFCE57);
        else if(entry.panel==notepad)PixelIcon.FILE_TEXT.draw(canvas,x,y,size,.45f,0xFF69D1E9);
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
