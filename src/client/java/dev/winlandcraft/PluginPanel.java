package dev.winlandcraft;

import dev.winlandcraft.api.v1.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;

/** Internal adapter. Plugins never inherit Minecraft or window implementation classes. */
final class PluginPanel extends BrowserPanel {
    final AppDefinition definition;
    private App app;
    private PluginFrames frames;private PluginAudio audio;
    private Context context;
    private String title,error="",url="about:blank",lastAddress="",lastTitle="";
    private boolean keyboard,nativeFocus=true,fullBrowser=true;
    private int bx,by,bw,bh;
    private final Set<Integer> nativeButtons=new HashSet<>(),browserButtons=new HashSet<>(),nativeKeys=new HashSet<>();
    PluginPanel(AppDefinition definition){super("about:blank");this.definition=definition;title=definition.name();layoutWidth=definition.width();layoutHeight=definition.height();scaleTo(layoutWidth/400f,layoutHeight/400f);}
    @Override public String windowTitle(){return title;}
    @Override protected boolean browserEnabled(){return definition.kind()!=AppKind.NATIVE&&error.isEmpty();}
    @Override protected String initialUrl(){return url;}
    @Override protected int browserWidth(){return fullBrowser?pixelWidth():Math.max(1,Math.min(bw,pixelWidth()-bx));}
    @Override protected int browserHeight(){return fullBrowser?pixelHeight():Math.max(1,Math.min(bh,pixelHeight()-by));}
    @Override void remoteControl(StreamProtocol.Control input){remoteInput.accept(this,input);}
    @Override protected boolean showBrowserMenu(){return false;}
    @Override protected void managedLoaded(){if(app!=null)call(app::onBrowserLoaded); }
    @Override protected boolean projectsLight(){return true;}
    private int browserX(){return fullBrowser?0:Math.min(bx,pixelWidth()-1);}
    private int browserY(){return fullBrowser?0:Math.min(by,pixelHeight()-1);}
    private boolean inBrowser(int x,int y){return browserEnabled()&&x>=browserX()&&y>=browserY()&&x<browserX()+browserWidth()&&y<browserY()+browserHeight();}
    private void closeMedia(){if(frames!=null){frames.close();frames=null;}if(audio!=null){audio.close();audio=null;}}
    @Override protected void tabSelected(com.cinemamod.mcef.MCEFBrowser browser){super.tabSelected(audio==null?browser:null);}
    private void fail(Throwable failure){error="Plugin failed: "+definition.name()+". See latest.log.";keyboard=false;WinLandCraftClient.LOGGER.error("Plugin app {} failed",definition.id(),failure);closeMedia();closeViews();}
    private void call(Runnable action){if(!error.isEmpty())return;try{action.run();}catch(RuntimeException|LinkageError|AssertionError failure){fail(failure);}}
    private void initialize(){if(app!=null||!error.isEmpty()||!isOpen())return;call(()->{app=definition.create();context=new Context();app.onOpen(context);if(app!=null)app.onResize(pixelWidth(),pixelHeight());});}
    @Override public void open(Minecraft client){super.open(client);initialize();}
    @Override public void tick(Minecraft client){
        if(!isOpen())return;initialize();super.tick(client);if(!isOpen())return;
        if(app!=null)call(app::onTick);
        String address=managedAddress(),pageTitle=managedTitle();
        if(browserEnabled()&&app!=null&&!address.equals(lastAddress)){lastAddress=address;call(()->app.onBrowserAddressChanged(address));}
        if(browserEnabled()&&app!=null&&!pageTitle.equals(lastTitle)){lastTitle=pageTitle;call(()->app.onBrowserTitleChanged(pageTitle));}
    }
    @Override public void resize(float width,float height){int w=pixelWidth(),h=pixelHeight();super.resize(width,height);if(app!=null&&(w!=pixelWidth()||h!=pixelHeight()))call(()->app.onResize(pixelWidth(),pixelHeight()));}
    @Override public void close(){
        if(!com.mojang.blaze3d.systems.RenderSystem.isOnRenderThread()){com.mojang.blaze3d.systems.RenderSystem.recordRenderCall(this::close);return;}
        if(context!=null)context.live=false;
        closeMedia();
        App previous=app;app=null;context=null;
        if(previous!=null)try{previous.onClose();}catch(RuntimeException|LinkageError|AssertionError failure){WinLandCraftClient.LOGGER.error("Plugin close failed: {}",definition.id(),failure);}
        nativeButtons.clear();browserButtons.clear();nativeKeys.clear();keyboard=false;error="";title=definition.name();lastAddress=lastTitle="";url="about:blank";fullBrowser=true;
        super.close();
    }
    @Override public boolean acceptsKeyboard(){return app!=null&&error.isEmpty();}
    @Override public boolean wantsKeyboard(){return keyboard&&acceptsKeyboard();}
    @Override public void keyboardStarted(){keyboard=true;if(app!=null)call(()->app.onFocusChanged(true));}
    @Override public void keyboardStopped(){
        keyboard=false;releaseInputs();
        if(app!=null){for(int key:List.copyOf(nativeKeys))call(()->app.onKey(key,0,0,0));call(()->app.onFocusChanged(false));}
        nativeKeys.clear();super.keyboardStopped();
    }
    @Override public void hover(int x,int y){
        if(app==null)return;
        if(!nativeButtons.isEmpty()||!inBrowser(x,y))call(()->app.onPointerMove(x,y));else call(()->app.onPointerMove(-1,-1));
        if(browserEnabled())super.hover(x-browserX(),y-browserY());
    }
    @Override public void mouseDown(int x,int y,int button){
        initialize();if(app==null||!error.isEmpty())return;
        boolean web=inBrowser(x,y);if(web==nativeFocus)keyboardStopped();nativeFocus=!web;
        if(web){browserButtons.add(button);keyboard=true;super.mouseDown(x-browserX(),y-browserY(),button);}
        else {nativeButtons.add(button);call(()->app.onPointerDown(x,y,button));}
    }
    @Override public void mouseUp(int x,int y,int button){
        if(browserButtons.remove(button))super.mouseUp(x-browserX(),y-browserY(),button);
        if(nativeButtons.remove(button)&&app!=null)call(()->app.onPointerUp(x,y,button));
    }
    @Override public void scroll(int x,int y,double amount){if(inBrowser(x,y))super.scroll(x-browserX(),y-browserY(),amount);else if(app!=null)call(()->app.onScroll(x,y,amount));}
    @Override public void key(int key,int scan,int action,int modifiers){
        if(!nativeFocus){super.key(key,scan,action,modifiers);return;}
        if(action==0)nativeKeys.remove(key);else nativeKeys.add(key);
        if(app!=null)call(()->app.onKey(key,scan,action,modifiers));
    }
    @Override public void character(char character,int modifiers){if(!nativeFocus)super.character(character,modifiers);else if(app!=null)call(()->app.onCharacter(character,modifiers));}
    @Override public boolean acceptsFileDrop(){return !definition.extensions().isEmpty()||!definition.fileNames().isEmpty();}
    @Override public void dropFile(Path path){initialize();if(app!=null){if(definition.accepts(path))call(()->app.openFile(path));else WinLandCraftClient.LOGGER.debug("Plugin {} rejected file {}",definition.id(),path);}}
    @Override public Path dragFileAt(int x,int y){if(app==null||inBrowser(x,y))return null;Path[] result={null};call(()->result[0]=app.dragFileAt(x,y));return result[0];}
    @Override void drawSurface(PanelCanvas canvas){
        canvas.rect(0,0,pixelWidth(),pixelHeight(),.1f,0xFF18212D);
        canvas.frame(pixelWidth(),pixelHeight(),titlebarHeight());
        if(!error.isEmpty()){canvas.text(error,16,24,0xFFFFA5A5,1.5f);return;}
        if(browserEnabled())drawManagedBrowser(canvas,browserX(),browserY(),browserWidth(),browserHeight());
        if(frames!=null)call(()->frames.draw(canvas,pixelWidth(),pixelHeight()));
        if(app!=null){var frame=new Drawing(canvas);try{call(()->app.render(frame));}finally{frame.live=false;}}
    }
    private final class Drawing implements Canvas,dev.winlandcraft.api.v2.Canvas {
        private final PanelCanvas canvas;private boolean live=true;private int draws;
        Drawing(PanelCanvas canvas){this.canvas=canvas;}
        private float layer(){if(!live)throw new IllegalStateException("Canvas expired");if(++draws>8192)throw new IllegalStateException("Too many draw calls");return .3f+draws*.0001f;}
        public int width(){return pixelWidth();}public int height(){return pixelHeight();}
        public int textWidth(String text){return Minecraft.getInstance().font.width(text);}
        public void rectangle(float x,float y,float w,float h,int color){canvas.rect(x,y,w,h,layer(),color);}
        public void text(String text,int x,int y,int color,float scale){canvas.text(text,x,y,color,scale,layer());}
        public void image(String resource,int x,int y,int w,int h,int color){canvas.texture(ResourceLocation.parse(resource),x,y,w,h,layer(),color,0,0,1,1);}
    }
    private final class Context implements WindowContext,dev.winlandcraft.api.v2.WindowContext {
        private volatile boolean live=true;private final AtomicInteger pending=new AtomicInteger();
        private final View browser=new View(this);
        private void check(){if(!live||context!=this)throw new IllegalStateException("Window session closed");if(!Minecraft.getInstance().isSameThread())throw new IllegalStateException("Use window.execute from worker threads");}
        public int width(){check();return pixelWidth();}public int height(){check();return pixelHeight();}
        public void title(String value){check();title=Objects.requireNonNull(value);if(title.length()>512)title=title.substring(0,512);}
        public void requestKeyboard(boolean requested){check();keyboard=requested;nativeFocus=true;if(WinLandCraftClient.controls!=null)WinLandCraftClient.controls.requestPluginKeyboard(PluginPanel.this,requested);}
        public void close(){check();PluginPanel.this.close();}
        public void execute(Runnable action){Objects.requireNonNull(action);if(!live)return;if(pending.incrementAndGet()>128){pending.decrementAndGet();throw new IllegalStateException("Too many pending window completions");}Minecraft.getInstance().execute(()->{try{if(live&&context==this)call(action);}finally{pending.decrementAndGet();}});}
        public Path dataDirectory(){return net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("winlandcraft/plugins").resolve(definition.id().replace(':','/'));}
        public String clipboard(){check();return Minecraft.getInstance().keyboardHandler.getClipboard();}
        public void clipboard(String text){check();Minecraft.getInstance().keyboardHandler.setClipboard(Objects.requireNonNull(text));}
        public dev.winlandcraft.api.v2.FrameSurface frames(){check();if(frames==null)frames=new PluginFrames();return frames;}
        public dev.winlandcraft.api.v2.AudioOutput audio(){check();if(audio==null){audio=new PluginAudio(PluginPanel.this);audioTab=null;}return audio;}
        public View browser(){check();if(definition.kind()==AppKind.NATIVE)throw new IllegalStateException("Native apps do not own a browser view");return browser;}
    }
    private final class View implements BrowserView,dev.winlandcraft.api.v2.BrowserView {
        private final Context owner;View(Context owner){this.owner=owner;}
        public void navigate(String value){owner.check();Objects.requireNonNull(value);if(value.length()>2_000_000)throw new IllegalArgumentException("URL too large");url=value;if(managedBrowser()!=null)managedBrowser().loadURL(url);}
        public String address(){owner.check();return managedAddress();}public String title(){owner.check();return managedTitle();}
        public boolean ready(){owner.check();return managedBrowser()!=null&&managedBrowser().getIdentifier()>=0;}
        public void back(){owner.check();if(ready())managedBrowser().goBack();}public void forward(){owner.check();if(ready())managedBrowser().goForward();}public void reload(){owner.check();if(ready())managedBrowser().reload();}
        public boolean executeJavaScript(String script){owner.check();if(script.length()>1_000_000)throw new IllegalArgumentException("Script too large");if(!ready())return false;managedBrowser().executeJavaScript(script,address(),0);return true;}
        public void bounds(int x,int y,int width,int height){owner.check();if(x<0||y<0||width<1||height<1)throw new IllegalArgumentException("Invalid browser bounds");bx=x;by=y;bw=width;bh=height;fullBrowser=false;}
        public void fillWindow(){owner.check();fullBrowser=true;}
    }
}
