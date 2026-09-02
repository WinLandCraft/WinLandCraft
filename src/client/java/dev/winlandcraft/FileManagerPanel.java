package dev.winlandcraft;

import java.nio.file.*;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import org.lwjgl.glfw.GLFW;

/** Native, read-only directory browser; no CEF view and no shell/file launching. */
public final class FileManagerPanel extends WorldPanel {
    private Path directory;
    private volatile FileDirectory.Listing result;
    private final List<Path> history=new ArrayList<>();
    private int historyIndex=-1,first,sideFirst,selected=-1,hoverRow=-1;
    private long lastClick;private int lastRow=-1;
    private volatile long request;
    private Thread worker;
    private boolean editing,selectAll;
    private String address="",notice="";
    public FileManagerPanel(){super(3.2f,1.8f);}
    @Override public int pixelWidth(){return 1280;}
    @Override public int pixelHeight(){return 720;}
    @Override public int titlebarHeight(){return 32;}
    @Override protected float minimumWidth(){return 1.6f;}
    @Override protected float minimumHeight(){return .9f;}
    @Override public void open(Minecraft client){super.open(client);if(directory==null)navigate(FileDirectory.home(),true);}
    private void navigate(Path path,boolean remember) {
        path=path.toAbsolutePath().normalize();directory=path;address=path.toString();editing=false;
        first=0;selected=-1;lastRow=-1;notice="";result=null;
        if(remember) {
            while(history.size()>historyIndex+1)history.remove(history.size()-1);
            history.add(path);historyIndex=history.size()-1;
        }
        long id=++request;if(worker!=null)worker.interrupt();Path target=path;
        worker=Thread.startVirtualThread(()->{var listing=FileDirectory.read(target);if(request==id)result=listing;});
    }
    @Override public void close(){super.close();request++;if(worker!=null)worker.interrupt();result=null;directory=null;history.clear();historyIndex=-1;editing=false;}
    @Override public boolean acceptsKeyboard(){return true;}
    @Override public boolean wantsKeyboard(){return editing;}
    @Override public void keyboardStopped(){editing=false;if(directory!=null)address=directory.toString();}
    @Override public void character(char ch,int modifiers){if(editing&&!Character.isISOControl(ch)){if(selectAll){address="";selectAll=false;}if(address.length()<4096)address+=ch;}}
    @Override public void key(int key,int scan,int action,int modifiers) {
        if(!editing||action==GLFW.GLFW_RELEASE)return;
        if(key==GLFW.GLFW_KEY_A&&(modifiers&GLFW.GLFW_MOD_CONTROL)!=0){selectAll=true;return;}
        if(key==GLFW.GLFW_KEY_V&&(modifiers&GLFW.GLFW_MOD_CONTROL)!=0){
            String text=Minecraft.getInstance().keyboardHandler.getClipboard();
            for(char ch:text.toCharArray())character(ch,0);return;
        }
        if(key==GLFW.GLFW_KEY_BACKSPACE){if(selectAll)address="";else if(!address.isEmpty())address=address.substring(0,address.offsetByCodePoints(address.length(),-1));selectAll=false;}
        if(key==GLFW.GLFW_KEY_ENTER||key==GLFW.GLFW_KEY_KP_ENTER)try {
            Path path=Path.of(address);navigate(path.isAbsolute()?path:directory.resolve(path),true);
        }catch(InvalidPathException error){notice="Invalid folder path.";}
    }
    @Override public void hover(int x,int y){hoverRow=x>=240&&x<1260&&y>=112&&y<656?(y-112)/34+first:-1;}
    @Override public Path dragFileAt(int x,int y){
        var r=result;if(r==null||x<240||x>=1260||y<112||y>=656)return null;
        int row=(y-112)/34+first;
        return row<r.entries().size()&&!r.entries().get(row).directory()?r.entries().get(row).path():null;
    }
    @Override public void scroll(int x,int y,double amount){var r=result;if(r==null||y<0)return;if(x<240)sideFirst=Math.clamp(sideFirst-(int)Math.signum(amount),0,Math.max(0,r.locations().size()-14));else first=Math.clamp(first-(int)Math.signum(amount)*3,0,Math.max(0,r.entries().size()-16));}
    @Override public void mouseDown(int x,int y,int button) {
        if(button!=0||y<0)return;
        if(y>=14&&y<58) {
            if(x>=260&&x<1256){editing=true;selectAll=true;return;}
            if(x>=12&&x<62&&historyIndex>0)navigate(history.get(--historyIndex),false);
            else if(x>=70&&x<120&&historyIndex+1<history.size())navigate(history.get(++historyIndex),false);
            else if(x>=128&&x<178&&directory!=null&&directory.getParent()!=null)navigate(directory.getParent(),true);
            else if(x>=186&&x<246&&directory!=null)navigate(directory,false);
            return;
        }
        var r=result;if(r==null)return;
        if(x>=12&&x<228&&y>=112&&y<588){int row=(y-112)/34+sideFirst;if(row<r.locations().size())navigate(r.locations().get(row).path(),true);return;}
        if(x>=240&&x<1260&&y>=112&&y<656) {
            int row=(y-112)/34+first;if(row>=r.entries().size())return;
            selected=row;var entry=r.entries().get(row);long now=System.nanoTime();
            if(row==lastRow&&now-lastClick<450_000_000L&&entry.directory())navigate(entry.path(),true);
            else {notice=entry.directory()?"Double-click to enter this folder.":"Hold left mouse and drag this file onto Notepad.";lastRow=row;lastClick=now;}
        }
    }
    private static String fit(String value,int width,float scale){return Minecraft.getInstance().font.plainSubstrByWidth(value,(int)(width/scale));}
    private static String size(long bytes){if(bytes<0)return "-";if(bytes<1024)return bytes+" B";if(bytes<1048576)return String.format(Locale.ROOT,"%.1f KiB",bytes/1024.0);return String.format(Locale.ROOT,"%.1f MiB",bytes/1048576.0);}
    static void folder(PanelCanvas c,int x,int y,int size){PixelIcon.FOLDER.draw(c,x,y,size,.45f,0xFFFFCE57);}
    @Override public void render(WorldRenderContext context) {
        try(var c=canvas(context)) {
            if(c==null)return;
            c.rect(-3,-35,1286,758,0,0xFF536579);c.rect(0,-32,1280,32,.3f,0xFF314D63);
            c.text(fit("File Manager"+(directory==null?"":" - "+directory),grouped()?1090:1190,1.5f),12,-22,-1,1.5f);renderUngroup(c);
            renderClose(c);
            c.rect(0,0,1280,720,.1f,0xFF17212D);c.rect(0,76,232,602,.2f,0xFF202C3B);
            PixelIcon[] buttons={PixelIcon.ARROW_LEFT,PixelIcon.ARROW_RIGHT,PixelIcon.ARROW_UP,PixelIcon.REFRESH};int[] xs={12,70,128,186};
            boolean[] enabled={historyIndex>0,historyIndex+1<history.size(),directory!=null&&directory.getParent()!=null,directory!=null};
            for(int i=0;i<4;i++){int width=i==3?60:50;colorButton(c,buttons[i],xs[i],14,width,44,enabled[i]);}
            c.rect(260,14,996,44,.3f,editing?0xFF39566F:hoverColor(260,14,996,44,0xFF101B28,0xFF1D3042));
            String shown=editing?address+(selectAll?"":"|"):directory==null?"Home":directory.toString();
            c.text(fit(shown,972,1.5f),272,29,selectAll&&editing?0xFF72ECF1:-1,1.5f);
            c.text("Quick locations",16,86,0xFF9BADBF,1.5f);
            c.text("Name",280,86,0xFF9BADBF,1.5f);c.text("Modified",780,86,0xFF9BADBF,1.5f);c.text("Type",1020,86,0xFF9BADBF,1.5f);c.text("Size",1150,86,0xFF9BADBF,1.5f);
            var r=result;
            if(r==null){c.text("Reading folder...",260,124,0xFFB8CBDE,1.5f);return;}
            for(int i=0;i<14&&sideFirst+i<r.locations().size();i++) {
                var loc=r.locations().get(sideFirst+i);int y=112+i*34;
                if(loc.path().equals(directory)||hovered(12,y,216,32))c.rect(12,y,216,32,.3f,loc.path().equals(directory)?0xFF395269:0xFF2B4052);
                folder(c,16,y+8,22);c.text(fit(loc.name(),172,1.5f),46,y+9,-1,1.5f);
            }
            var date=java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(java.time.ZoneId.systemDefault());
            for(int i=0;i<16&&first+i<r.entries().size();i++) {
                int index=first+i,y=112+i*34;var entry=r.entries().get(index);
                if(index==selected||index==hoverRow)c.rect(242,y,1018,32,.3f,index==selected?0xFF39566F:0xFF283B4D);
                if(entry.directory())folder(c,252,y+7,22);else PixelIcon.FILE_TEXT.draw(c,252,y+5,24,.45f,0xFFB6C9DC);
                c.text(fit(entry.name(),480,1.5f),284,y+9,-1,1.5f);
                c.text(entry.modified()==0?"-":date.format(java.time.Instant.ofEpochMilli(entry.modified())),780,y+10,0xFFB8CBDE,1.25f);
                c.text(entry.directory()?"Folder":entry.link()?"Link":"File",1020,y+10,0xFFB8CBDE,1.25f);
                c.text(entry.directory()?"-":size(entry.size()),1150,y+10,0xFFB8CBDE,1.25f);
            }
            if(!r.error().isEmpty())c.text(r.error(),260,124,0xFFFFA5A5,1.5f);
            else if(r.entries().isEmpty())c.text("This folder is empty.",260,124,0xFFB8CBDE,1.5f);
            if(r.entries().size()>16){float h=Math.max(16,544f*16/r.entries().size());c.rect(1266,112,5,544,.3f,0xFF283B4D);c.rect(1266,112+(544-h)*first/(r.entries().size()-16f),5,h,.4f,0xFF8AA6BD);}
            c.text(r.entries().size()+" items"+(r.truncated()?" (first 10,000 shown)":"")+" | Scroll to browse",16,689,0xFF9BADBF,1.25f);
            c.text(fit(notice,790,1.25f),470,689,0xFFB8CBDE,1.25f);
        }
    }
    private void colorButton(PanelCanvas c,PixelIcon icon,int x,int y,int width,int height,boolean enabled){
        int color=enabled?hoverColor(x,y,width,height,0xFF304457,0xFF426079):0xFF263542;
        c.rect(x,y,width,height,.3f,color);icon.draw(c,x+(width-24)/2f,y+10,24,.45f,enabled?-1:0xFF758393);
    }
}
