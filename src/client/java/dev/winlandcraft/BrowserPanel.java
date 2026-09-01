package dev.winlandcraft;

import com.cinemamod.mcef.MCEF;
import com.cinemamod.mcef.MCEFBrowser;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.glfw.GLFW;
import java.util.*;

/** Native sidebar and independent CEF tabs, all in one shared MCEF runtime. */
public class BrowserPanel extends WorldPanel {
    private static final int ROW = 44;
    private final int SIDE;
    private final String homeUrl;
    private final boolean standalone;
    private final List<Tab> tabs = new ArrayList<>();

    private static final Object LOCAL_INPUT = new Object();
    private record Input(Object source, int code) { }
    private final Map<Input, Tab> pressed = new HashMap<>();
    private final Map<Input, Tab> pressedKeys = new HashMap<>();
    private Tab active;
    private static int nextId;
    private int firstTab;
    private boolean failed, editing, selectAll;
    private String address = "";
    private int caret;
    private ContextMenu menu;
    public int viewCount() { return tabs.size(); }
    public long estimatedTextureBytes() {
        return tabs.stream().mapToLong(tab -> (long) tab.width * tab.height * 4).sum();
    }
    private String appName = "Browser";
    public void setAppName(String name) { appName = name; }
    protected int listTop(){return 176;}
    protected void drawSidebarExtras(PanelCanvas canvas){}
    protected boolean sidebarExtraClick(int x,int y,int button){return false;}
    protected void browserCreated(MCEFBrowser browser){}
    protected void browserClosed(MCEFBrowser browser){}
    protected void tabSelected(MCEFBrowser browser){}
    @Override public int titlebarHeight() { return 32; }
    public String windowTitle() {
        return active != null && !active.title.isBlank() ? active.title + " - " + appName : appName;
    }

    public BrowserPanel() { this(null); }
    public BrowserPanel(String appUrl) {
        super(3.2f, 1.8f); standalone = appUrl != null;
        homeUrl = standalone ? appUrl : "https://www.google.com/";
        SIDE = standalone ? 0 : 260;
    }
    private int layoutWidth = 1280, layoutHeight = 720;
    @Override public int pixelWidth() { return layoutWidth; }
    @Override public int pixelHeight() { return layoutHeight; }
    @Override protected float minimumWidth() { return standalone ? 0.8f : 1.3f; }
    @Override protected float minimumHeight() { return standalone ? 0.6f : 0.8f; }
    private int visibleRows() { return Math.max(1, (pixelHeight() - listTop() - 60) / ROW); }
    @Override public void resize(float width, float height) {
        float densityX = layoutWidth / worldWidth(), densityY = layoutHeight / worldHeight();
        super.resize(width, height);
        layoutWidth = Math.max(SIDE+1, Math.round(worldWidth() * densityX));
        layoutHeight = Math.max(1, Math.round(worldHeight() * densityY));
        menu = null;
    }
    @Override public boolean acceptsKeyboard() { return active != null; }
    @Override public boolean wantsKeyboard() { return editing; }
    @Override public void keyboardStopped() { editing = false; selectAll = false; }

    public void open(Minecraft client) {
        bringToView(client.level, client.gameRenderer.getMainCamera(), 0.6f);
        failed = false;
    }

    private void registerEvents(Tab tab) {
        BrowserEvents.add(tab.browser, new BrowserEvents.Listener() {
            @Override public void address(String url) {
                tab.url = url;
                if (!standalone) { tab.icon.clear(); tab.refreshAt = System.nanoTime() + 500_000_000L; }
            }
            @Override public void title(String title) { tab.title = title; }
            @Override public void loaded() { tab.refreshAt = System.nanoTime() + 500_000_000L; }
            @Override public void menu(int x, int y, String link) {
                if (active == tab) menu = new ContextMenu(Math.clamp(x + SIDE, SIDE, pixelWidth() - 240), Math.clamp(y, 0, pixelHeight() - 180), link);
            }
            @Override public void popup(String url) {
                if (!webLink(url)) return;
                if (standalone) tab.browser.loadURL(url); else newTab(url);
            }
        });
    }
    private void newTab(String url) {
        if (!MCEF.isInitialized()) return;
        Tab tab = null;
        try {

            tab = new Tab(++nextId, url, pixelWidth() - SIDE, pixelHeight());
            tabs.add(tab);
            registerEvents(tab);
            select(tab);
            failed = false;
        } catch (RuntimeException | LinkageError error) {
            if (tab != null) { tabs.remove(tab); tab.close(); }
            failed = true;
            WinLandCraftClient.LOGGER.error("Could not create browser tab", error);
        }
    }
    private void select(Tab tab) {
        releaseInputs();
        if (active != null) active.browser.setFocus(false);
        active = tab; editing = false; menu = null;
        tabSelected(tab.browser);
        int index = tabs.indexOf(tab);
        if (index < firstTab) firstTab = index;
        if (index >= firstTab + visibleRows()) firstTab = index - visibleRows() + 1;
    }
    private void closeTab(Tab tab) {
        int index = tabs.indexOf(tab);
        if (index < 0) return;
        releaseInputs();
        tabs.remove(tab); tab.close(); menu = null;
        if (active == tab) {
            active = null; editing = false;
            tabSelected(null);
            if (!tabs.isEmpty()) select(tabs.get(Math.min(index, tabs.size() - 1)));
            else newTab(homeUrl);
        }
        firstTab = Math.clamp(firstTab, 0, Math.max(0, tabs.size() - visibleRows()));
    }
    private void releaseInputs() {
        pressed.forEach((input, tab) -> tab.browser.sendMouseRelease(-1, -1, input.code()));
        pressed.clear();
        pressedKeys.forEach((input, tab) -> tab.browser.sendKeyRelease(input.code(), 0, 0));
        pressedKeys.clear();
    }
    protected final void releaseInputSource(Object source) {
        var mouse=pressed.entrySet().stream().filter(entry->entry.getKey().source().equals(source)).toList();
        var keys=pressedKeys.entrySet().stream().filter(entry->entry.getKey().source().equals(source)).toList();
        mouse.forEach(entry->pressed.remove(entry.getKey()));keys.forEach(entry->pressedKeys.remove(entry.getKey()));
        mouse.forEach(entry->{if(!held(pressed,entry.getValue(),entry.getKey().code()))entry.getValue().browser.sendMouseRelease(-1,-1,entry.getKey().code());});
        keys.forEach(entry->{if(!held(pressedKeys,entry.getValue(),entry.getKey().code()))entry.getValue().browser.sendKeyRelease(entry.getKey().code(),0,0);});
    }
    private static boolean held(Map<Input,Tab> inputs,Tab tab,int code) {
        for(var entry:inputs.entrySet())if(entry.getValue()==tab&&entry.getKey().code()==code)return true;
        return false;
    }
    @Override public void close() {
        // MCEFBrowser.close deletes GL textures synchronously; never run it on Netty/CEF threads.
        if (!com.mojang.blaze3d.systems.RenderSystem.isOnRenderThread()) {
            com.mojang.blaze3d.systems.RenderSystem.recordRenderCall(this::close);
            return;
        }
        super.close(); releaseInputs();

        for (Tab tab : tabs) tab.close();
        tabs.clear(); active = null; menu = null; editing = false; firstTab = 0; failed = false;
    }
    private void editAddress() {
        if (active == null) return;
        releaseInputs(); active.browser.setFocus(false);
        address = active.url; caret = address.length(); selectAll = true; editing = true;
    }
    @Override public void key(int key, int scan, int action, int modifiers) { key(LOCAL_INPUT,key,scan,action,modifiers); }
    protected final void key(Object source,int key,int scan,int action,int modifiers) {
        var input=new Input(source,key);
        if (action == GLFW.GLFW_RELEASE) {
            Tab tab = pressedKeys.remove(input);
            if (tab != null&&!held(pressedKeys,tab,key)) tab.browser.sendKeyRelease(key, scan, modifiers);
            return;
        }
        if (active == null) return;
        boolean ctrl = !standalone && (modifiers & GLFW.GLFW_MOD_CONTROL) != 0;
        if (ctrl && key == GLFW.GLFW_KEY_L) { editAddress(); return; }
        if (ctrl && key == GLFW.GLFW_KEY_T) { newTab(homeUrl); editAddress(); return; }
        if (ctrl && key == GLFW.GLFW_KEY_W) { closeTab(active); return; }
        if (!editing) {
            active.browser.setFocus(true);boolean first=!held(pressedKeys,active,key);pressedKeys.put(input,active);
            if(first||action==GLFW.GLFW_REPEAT)active.browser.sendKeyPress(key,scan,modifiers);return;
        }
        if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
            String url = BrowserAddress.destination(address);
            if (url != null) { active.url = url; active.browser.loadURL(url); editing = false; selectAll = false; }
        } else if (ctrl && key == GLFW.GLFW_KEY_A) selectAll = true;
        else if (ctrl && (key == GLFW.GLFW_KEY_C || key == GLFW.GLFW_KEY_X)) {
            if (selectAll) { Minecraft.getInstance().keyboardHandler.setClipboard(address); if (key == GLFW.GLFW_KEY_X) insert(""); }
        } else if (ctrl && key == GLFW.GLFW_KEY_V) insert(Minecraft.getInstance().keyboardHandler.getClipboard());
        else if (key == GLFW.GLFW_KEY_BACKSPACE) {
            if (selectAll) insert("");
            else if (caret > 0) { int previous = address.offsetByCodePoints(caret, -1); address = address.substring(0, previous) + address.substring(caret); caret = previous; }
        } else if (key == GLFW.GLFW_KEY_DELETE) {
            if (selectAll) insert("");
            else if (caret < address.length()) address = address.substring(0, caret) + address.substring(address.offsetByCodePoints(caret, 1));
        } else if (key == GLFW.GLFW_KEY_HOME) { caret = 0; selectAll = false; }
        else if (key == GLFW.GLFW_KEY_END) { caret = address.length(); selectAll = false; }
        else if (key == GLFW.GLFW_KEY_LEFT) { caret = selectAll ? 0 : caret > 0 ? address.offsetByCodePoints(caret, -1) : 0; selectAll = false; }
        else if (key == GLFW.GLFW_KEY_RIGHT) { caret = selectAll ? address.length() : caret < address.length() ? address.offsetByCodePoints(caret, 1) : caret; selectAll = false; }
    }
    private void insert(String text) {
        text = text.replaceAll("[\\p{Cntrl}]", "");
        if (selectAll) { address = ""; caret = 0; selectAll = false; }
        int room = Math.max(0, 8192 - address.length());
        if (text.length() > room) text = text.substring(0, room);
        address = address.substring(0, caret) + text + address.substring(caret); caret += text.length();
    }
    @Override public void character(char character, int modifiers) {
        if (editing) { if ((modifiers & GLFW.GLFW_MOD_CONTROL) == 0 && !Character.isISOControl(character)) insert(String.valueOf(character)); }
        else if (active != null) active.browser.sendKeyTyped(character, modifiers);
    }
    @Override public void mouseDown(int x, int y, int button) { mouseDown(LOCAL_INPUT,x,y,button); }
    protected final void mouseDown(Object source,int x,int y,int button) {
        if (y < 0) return;
        if(x<SIDE&&sidebarExtraClick(x,y,button))return;
        if (active == null) return;
        if (menu != null && button == 0) {
            var selected = menu; menu = null;
            if (x >= selected.x && x < selected.x + 240 && y >= selected.y && y < selected.y + 180) {
                switch ((y - selected.y) / 30) {
                    case 0 -> active.browser.goBack();
                    case 1 -> active.browser.goForward();
                    case 2 -> active.browser.reload();
                    case 3 -> { if (webLink(selected.link)) active.browser.loadURL(selected.link); }
                    case 4 -> { if (standalone) active.browser.loadURL(homeUrl); else if (webLink(selected.link)) newTab(selected.link); }
                    case 5 -> { if (standalone) close(); else closeTab(active); }
                }
            }
            return;
        }
        menu = null;
        if (x < SIDE) {
            if (button != 0) return;
            if (x >= 12 && x < 248 && y >= 12 && y < 60) { editAddress(); return; }
            editing = false;
            if (y >= 72 && y < 110 && x >= 12 && x < 248) {
                if (x < 88) active.browser.goBack(); else if (x < 168) active.browser.goForward(); else active.browser.reload();
            } else if (y >= 126 && y < 164 && x >= 12 && x < 248) { newTab(homeUrl); editAddress(); }
            else if (y >= listTop() && y < listTop() + visibleRows() * ROW && x >= 12 && x < 248) {
                int index = firstTab + (y - listTop()) / ROW;
                if (index < tabs.size()) { if (x >= 220) closeTab(tabs.get(index)); else select(tabs.get(index)); }
            }
            return;
        }
        editing = false;
        active.browser.setFocus(true); active.browser.sendMouseMove(x - SIDE, y);
        if(!held(pressed,active,button))active.browser.sendMousePress(x-SIDE,y,button);
        pressed.put(new Input(source,button),active);
    }
    private static boolean webLink(String url) { return url != null && (url.startsWith("https://") || url.startsWith("http://")); }
    @Override public void mouseUp(int x, int y, int button) { mouseUp(LOCAL_INPUT,x,y,button); }
    protected final void mouseUp(Object source,int x,int y,int button) {
        Tab tab = pressed.remove(new Input(source,button));
        if (tab != null&&!held(pressed,tab,button)) tab.browser.sendMouseRelease(x - SIDE, y, button);
    }
    @Override public void hover(int x, int y) { hover(LOCAL_INPUT,x,y); }
    protected final void hover(Object source,int x,int y) {
        if (active != null && menu == null) {
            boolean captured = false;
            for (var input : pressed.keySet()) if (input.source().equals(source)) { captured = true; break; }
            active.browser.sendMouseMove(x >= SIDE || captured ? x - SIDE : -1, x >= SIDE || captured ? y : -1);
        }
    }
    @Override public void scroll(int x, int y, double amount) {
        if (y < 0) return;
        if (x < SIDE) { firstTab = Math.clamp(firstTab - (int) Math.signum(amount), 0, Math.max(0, tabs.size() - visibleRows())); }
        else if (active != null && menu == null) { active.browser.sendMouseMove(x - SIDE, y); active.browser.sendMouseWheel(x - SIDE, y, amount, 0); }
    }
    @Override public void tick(Minecraft client) {
        super.tick(client);
        if (!isOpen()) return;
        if (tabs.isEmpty() && !failed && MCEF.isInitialized()) newTab(homeUrl);
        // At most once per game tick; page layout follows the new size instead of stretching 720p.
        for (Tab tab : tabs) tab.resize(pixelWidth() - SIDE, pixelHeight());
        firstTab = Math.clamp(firstTab, 0, Math.max(0, tabs.size() - visibleRows()));
        for (Tab tab : tabs) if (!standalone && tab.refreshAt != 0 && System.nanoTime() >= tab.refreshAt && !tab.browser.isLoading()) {
            tab.refreshAt = 0; tab.icon.refresh(tab.browser);
        }
    }
    private static String fit(String text, int width, float scale) {
        var font = Minecraft.getInstance().font;
        return font.width(text) * scale <= width ? text : font.plainSubstrByWidth(text, (int) (width / scale) - font.width("...")) + "...";
    }
    @Override public void render(WorldRenderContext context) {
        try (var canvas = canvas(context)) {
            if (canvas == null) return;
            drawSurface(canvas);
        }
    }
    void drawSurface(PanelCanvas canvas) {
            canvas.rect(-3, -titlebarHeight() - 3, pixelWidth() + 6, pixelHeight() + titlebarHeight() + 6, 0, 0xFF536579);
            canvas.rect(0, -titlebarHeight(), pixelWidth(), titlebarHeight(), 0.3f, 0xFF314D63);
            canvas.text(fit(windowTitle(), pixelWidth() - (grouped() ? 168 : 68), 1.5f), 12, -22, 0xFFF0F5FC, 1.5f);
            renderUngroup(canvas);
            canvas.rect(pixelWidth() - 40, -titlebarHeight(), 40, titlebarHeight(), 0.4f, 0xFF854551);
            canvas.text("X", pixelWidth() - 25, -22, 0xFFFFFFFF, 1.5f);
            canvas.rect(0, 0, pixelWidth(), pixelHeight(), 0.1f, 0xFF18212D);
            if (!standalone) {
                canvas.rect(0, 0, SIDE, pixelHeight(), 0.2f, 0xFF202C3B);
                canvas.rect(12, 12, 236, 48, 0.3f, editing ? 0xFF39566F : 0xFF121C29);
                String shown = editing ? address : active == null ? "Search or enter URL" : active.url;
                if (editing && !selectAll) {
                    String before = shown.substring(0, caret);
                    while (Minecraft.getInstance().font.width(before) * 1.5f > 208) before = before.substring(1);
                    shown = before + "|" + shown.substring(caret);
                }
                canvas.text(fit(shown, 216, 1.5f), 22, 30, selectAll && editing ? 0xFF71E6EE : 0xFFF0F5FC, 1.5f);
                String[] navigation = {"<", ">", "Reload"};
                for (int i = 0; i < 3; i++) {
                    canvas.rect(12 + i * 80, 72, 76, 38, 0.3f, 0xFF31475D);
                    boolean enabled = active != null && (i == 2 || (i == 0 ? active.browser.canGoBack() : active.browser.canGoForward()));
                    canvas.text(navigation[i], 22 + i * 80, 85, enabled ? 0xFFFFFFFF : 0xFF758393, 1.5f);
                }
                canvas.rect(12, 126, 236, 38, 0.3f, 0xFF314D63);
                canvas.text("+ New tab", 24, 138, 0xFFFFFFFF, 1.5f);
                drawSidebarExtras(canvas);
                for (int row = 0; row < visibleRows() && firstTab + row < tabs.size(); row++) {
                    Tab tab = tabs.get(firstTab + row); int y = listTop() + row * ROW;
                    canvas.rect(12, y, 236, ROW - 4, 0.3f, tab == active ? 0xFF3B5870 : 0xFF283849);
                    if (tab == active) canvas.rect(12, y + 8, 3, 24, 0.4f, 0xFF51CFDF);
                    var icon = tab.icon.texture();
                    if (icon != null) canvas.texture(icon, 24, y + 8, 24, 24, 0.4f);
                    else canvas.browserIcon(24, y + 8, 24);
                    canvas.text(fit(tab.title.isBlank() ? tab.url : tab.title, 156, 1.5f), 56, y + 13, 0xFFFFFFFF, 1.5f);
                    canvas.text("X", 228, y + 13, 0xFFB8CBDE, 1.5f);
                }
                canvas.text(tabs.size() + " tabs | Scroll list", 16, pixelHeight() - 41, 0xFF9BAABD, 1.2f);
                canvas.text(editing ? "Enter: go | Esc: leave" : "Click URL to type | G: page", 16, pixelHeight() - 20, 0xFF9BAABD, 1.2f);
            }
            if (active != null && active.browser.getRenderer().getTextureID() > 0)
                canvas.texture(active.texture, SIDE, 0, pixelWidth() - SIDE, pixelHeight(), 0.2f);
            else canvas.text(failed ? "Browser failed. Reopen to retry." : "Waiting for Chromium...", SIDE + 40, 40, 0xFFFFFFFF, 2);
            if (menu != null) {
                canvas.rect(menu.x, menu.y, 240, 180, 0.4f, 0xFF18212D);
                String[] entries = {"Back", "Forward", "Reload", "Open link here", standalone ? "App home" : "Open in new tab", standalone ? "Close app" : "Close tab"};
                for (int i = 0; i < entries.length; i++) canvas.text(entries[i], menu.x + 12, menu.y + i * 30 + 6, 0xFFFFFFFF, 2);
            }
    }
    private record ContextMenu(int x, int y, String link) { }
    private final class Tab {
        final MCEFBrowser browser;
        final ResourceLocation texture;
        final WebsiteIcon icon;
        String url, title = "";
        int width, height;
        long refreshAt = System.nanoTime() + 500_000_000L;
        Tab(int id, String url, int webWidth, int webHeight) {
            this.url = url;
            width = webWidth; height = webHeight;
            icon = new WebsiteIcon(id);
            texture = ResourceLocation.fromNamespaceAndPath("winlandcraft", "browser_tab_" + id);
            browser = MCEF.createBrowser("about:blank", false, webWidth, webHeight);
            try {
                browserCreated(browser);
                browser.setCursorChangeListener(cursor -> { });
                browser.setFocus(false);
                Minecraft.getInstance().getTextureManager().register(texture, new AbstractTexture() {
                    @Override public int getId() { return browser.getRenderer().getTextureID(); }
                    @Override public void releaseId() { }
                    @Override public void close() { }
                });
                browser.loadURL(url);
            } catch (RuntimeException | LinkageError error) { browserClosed(browser);browser.close(); throw error; }
        }
        void close() {
            com.mojang.blaze3d.systems.RenderSystem.assertOnRenderThread();
            browserClosed(browser);
            BrowserEvents.remove(browser);
            icon.clear(); Minecraft.getInstance().getTextureManager().release(texture); browser.close();
        }
        void resize(int newWidth, int newHeight) {
            if (width == newWidth && height == newHeight) return;
            browser.resize(newWidth, newHeight);
            width = newWidth; height = newHeight;
        }
    }
}
