package dev.winlandcraft;

import java.nio.file.*;
import java.util.*;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

/** Native, read-only directory browser; no CEF view and no shell/file launching. */
public final class FileManagerPanel extends NativePanel {
    private static final int LIST_LEFT=240,LIST_TOP=112,ROW_HEIGHT=34,LIST_RIGHT_MARGIN=20,LIST_BOTTOM_MARGIN=64;
    private Path directory;
    private volatile FileDirectory.Listing result;
    private final List<Path> history=new ArrayList<>();
    private int historyIndex=-1,first,sideFirst,selected=-1,hoverRow=-1;
    private long lastClick;private int lastRow=-1;
    private volatile long request;
    private Thread worker;
    private boolean editing,selectAll;
    private String address="",notice="";
    private final AppWindows apps;
    private Path placementFile;
    private List<AppWindows.FileTarget> placementApps=List.of();
    private int placementApp;
    private boolean suppressFileDrag;
    public FileManagerPanel(){this(null);}
    public FileManagerPanel(AppWindows apps){super(3.2f,1.8f,1280,720);this.apps=apps;}
    private int[] placementButton(FileAppPlacement.Side side) {
        int cx=pixelWidth()/2,cy=pixelHeight()/2;
        return switch(side){case LEFT->new int[]{16,cy-28};case RIGHT->new int[]{pixelWidth()-72,cy-28};case ABOVE->new int[]{cx-28,16};case BELOW->new int[]{cx-28,pixelHeight()-72};};
    }
    private void drawPlacement(PanelCanvas c) {
        c.rect(0,0,pixelWidth(),pixelHeight(),.1f,0xFF152A28);
        String title="Where to create the window?";
        int width=Minecraft.getInstance().font.width(title);
        float scale=Math.min(2f,Math.max(.5f,(pixelWidth()-160f)/width));
        c.text(title,(int)((pixelWidth()-width*scale)/2),pixelHeight()/2-62,-1,scale);
        String name=fit(placementFile.getFileName().toString(),Math.max(1,pixelWidth()-180),1.25f);
        c.text(name,(int)((pixelWidth()-Minecraft.getInstance().font.width(name)*1.25f)/2),pixelHeight()/2-34,0xFFB6D9CF,1.25f);
        for(var side:FileAppPlacement.Side.values()) {
            var b=placementButton(side);int x=b[0],y=b[1];
            c.rect(x,y,56,56,.2f,hoverColor(x,y,56,56,0xFF25543E,0xFF397E59));
            int dx=side==FileAppPlacement.Side.LEFT?-1:side==FileAppPlacement.Side.RIGHT?1:0;
            int dy=side==FileAppPlacement.Side.ABOVE?-1:side==FileAppPlacement.Side.BELOW?1:0;
            for(int i=-12;i<=12;i++)c.rect(x+26+dx*i,y+26+dy*i,4,4,.4f,0xFF75F5A5);
            for(int i=0;i<=12;i++)for(int sign:new int[]{-1,1})c.rect(x+26+dx*(12-i)+dy*i*sign,y+26+dy*(12-i)+dx*i*sign,4,4,.4f,0xFF75F5A5);
        }
        if(!placementApps.isEmpty()){
            int left=pixelWidth()/2-160,top=pixelHeight()/2-8;
            c.rect(left,top,320,32,.2f,hoverColor(left,top,320,32,0xFF30473F,0xFF4A675A));
            c.text(fit("Open with: "+placementApps.get(placementApp).name()+(placementApps.size()>1?" >":""),300,1.25f),left+10,top+9,-1,1.25f);
        }
        int x=pixelWidth()/2-50,y=pixelHeight()/2+38;
        c.rect(x,y,100,34,.2f,hoverColor(x,y,100,34,0xFF30473F,0xFF4A675A));c.text("Cancel",x+20,y+10,-1,1.5f);
    }
    int visibleRows(){return Math.max(1,(pixelHeight()-LIST_TOP-LIST_BOTTOM_MARGIN)/ROW_HEIGHT);}
    int listBottom(){return LIST_TOP+visibleRows()*ROW_HEIGHT;}
    private int fileRowAt(int x,int y) {
        if(x<LIST_LEFT||x>=pixelWidth()-LIST_RIGHT_MARGIN||y<LIST_TOP||y>=listBottom())return -1;
        return first+(y-LIST_TOP)/ROW_HEIGHT;
    }
    private int modifiedX(){return pixelWidth()-500;}
    private boolean details(){return pixelWidth()>=1000;}
    @Override protected void layoutChanged(){var r=result;if(r!=null){first=Math.clamp(first,0,Math.max(0,r.entries().size()-visibleRows()));sideFirst=Math.clamp(sideFirst,0,Math.max(0,r.locations().size()-visibleRows()));}}
    @Override public boolean floatingControls(){return true;}
    @Override public String windowTitle(){return "File Manager"+(directory==null?"":" - "+directory);}
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
    @Override public void close(){super.close();request++;if(worker!=null)worker.interrupt();result=null;directory=null;history.clear();historyIndex=-1;editing=false;placementFile=null;}
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
    @Override public void hover(int x,int y){hoverRow=fileRowAt(x,y);}
    @Override public Path dragFileAt(int x,int y){
        var r=result;if(suppressFileDrag||placementFile!=null||r==null)return null;
        int row=fileRowAt(x,y);
        return row>=0&&row<r.entries().size()&&!r.entries().get(row).directory()?r.entries().get(row).path():null;
    }
    @Override public void scroll(int x,int y,double amount){var r=result;if(placementFile!=null||r==null||y<0)return;if(x<LIST_LEFT)sideFirst=Math.clamp(sideFirst-(int)Math.signum(amount),0,Math.max(0,r.locations().size()-visibleRows()));else first=Math.clamp(first-(int)Math.signum(amount)*3,0,Math.max(0,r.entries().size()-visibleRows()));}
    @Override public void mouseDown(int x,int y,int button) {
        suppressFileDrag=false;
        if(button!=0||y<0)return;
        if(placementFile!=null) {
            suppressFileDrag=true;
            for(var side:FileAppPlacement.Side.values()) {var b=placementButton(side);if(x>=b[0]&&x<b[0]+56&&y>=b[1]&&y<b[1]+56) {
                if(apps!=null&&!placementApps.isEmpty()&&apps.openFile(placementApps.get(placementApp).id(),this,placementFile,side))notice="Opened in "+placementApps.get(placementApp).name()+".";else notice="Could not open app. Close unused file windows and try again.";
                placementFile=null;return;
            }}
            if(x>=pixelWidth()/2-160&&x<pixelWidth()/2+160&&y>=pixelHeight()/2-8&&y<pixelHeight()/2+24&&!placementApps.isEmpty())placementApp=(placementApp+1)%placementApps.size();
            if(x>=pixelWidth()/2-50&&x<pixelWidth()/2+50&&y>=pixelHeight()/2+38&&y<pixelHeight()/2+72)placementFile=null;
            return;
        }
        if(y>=14&&y<58) {
            if(x>=260&&x<pixelWidth()-24){editing=true;selectAll=true;return;}
            if(x>=12&&x<62&&historyIndex>0)navigate(history.get(--historyIndex),false);
            else if(x>=70&&x<120&&historyIndex+1<history.size())navigate(history.get(++historyIndex),false);
            else if(x>=128&&x<178&&directory!=null&&directory.getParent()!=null)navigate(directory.getParent(),true);
            else if(x>=186&&x<246&&directory!=null)navigate(directory,false);
            return;
        }
        var r=result;if(r==null)return;
        if(x>=12&&x<228&&y>=LIST_TOP&&y<listBottom()){int row=(y-LIST_TOP)/ROW_HEIGHT+sideFirst;if(row<r.locations().size())navigate(r.locations().get(row).path(),true);return;}
        int row=fileRowAt(x,y);
        if(row>=0) {
            if(row>=r.entries().size())return;
            selected=row;var entry=r.entries().get(row);long now=System.nanoTime();
            if(row==lastRow&&now-lastClick<450_000_000L) {
                lastRow=-1;lastClick=0;
                if(entry.directory())navigate(entry.path(),true);
                else {
                    placementApps=apps==null?(FileAppPlacement.supported(entry.path())?List.of(new AppWindows.FileTarget("winlandcraft:notepad","Notepad")):List.of()):apps.fileTargets(entry.path());
                    placementApp=0;
                    if(!placementApps.isEmpty()){placementFile=entry.path();editing=false;}else notice="No app supports this file type yet.";
                }
            }
            else {notice=entry.directory()?"Double-click to enter this folder.":"Double-click to open, or drag this file onto a compatible app.";lastRow=row;lastClick=now;}
        }
    }
    private static String fit(String value,int width,float scale){return Minecraft.getInstance().font.plainSubstrByWidth(value,(int)(width/scale));}
    private static String size(long bytes){if(bytes<0)return "-";if(bytes<1024)return bytes+" B";if(bytes<1048576)return String.format(Locale.ROOT,"%.1f KiB",bytes/1024.0);return String.format(Locale.ROOT,"%.1f MiB",bytes/1048576.0);}
    static void folder(PanelCanvas c,int x,int y,int size){PixelIcon.FOLDER.draw(c,x,y,size,.45f,0xFFFFCE57);}
    @Override void drawSurface(PanelCanvas c) {
        if(placementFile!=null){drawPlacement(c);return;}
        c.rect(-3,-3,pixelWidth()+6,pixelHeight()+6,0,0xFF536579);
        c.rect(0,0,pixelWidth(),pixelHeight(),.1f,0xFF17212D);c.rect(0,76,232,Math.max(0,pixelHeight()-118),.2f,0xFF202C3B);
        PixelIcon[] buttons={PixelIcon.ARROW_LEFT,PixelIcon.ARROW_RIGHT,PixelIcon.ARROW_UP,PixelIcon.REFRESH};int[] xs={12,70,128,186};
        boolean[] enabled={historyIndex>0,historyIndex+1<history.size(),directory!=null&&directory.getParent()!=null,directory!=null};
        for(int i=0;i<4;i++){int width=i==3?60:50;colorButton(c,buttons[i],xs[i],14,width,44,enabled[i]);}
        c.rect(260,14,Math.max(1,pixelWidth()-284),44,.3f,editing?0xFF39566F:hoverColor(260,14,Math.max(1,pixelWidth()-284),44,0xFF101B28,0xFF1D3042));
        String shown=editing?address+(selectAll?"":"|"):directory==null?"Home":directory.toString();
        c.text(fit(shown,Math.max(1,pixelWidth()-308),1.5f),272,29,selectAll&&editing?0xFF72ECF1:-1,1.5f);
        c.text("Quick locations",16,86,0xFF9BADBF,1.5f);
        c.text("Name",280,86,0xFF9BADBF,1.5f);if(details()){c.text("Modified",modifiedX(),86,0xFF9BADBF,1.5f);c.text("Type",pixelWidth()-260,86,0xFF9BADBF,1.5f);c.text("Size",pixelWidth()-130,86,0xFF9BADBF,1.5f);}
        var r=result;
        if(r==null){c.text("Reading folder...",260,124,0xFFB8CBDE,1.5f);return;}
        for(int i=0;i<visibleRows()&&sideFirst+i<r.locations().size();i++) {
            var loc=r.locations().get(sideFirst+i);int y=LIST_TOP+i*ROW_HEIGHT;
            if(loc.path().equals(directory)||hovered(12,y,216,32))c.rect(12,y,216,32,.3f,loc.path().equals(directory)?0xFF395269:0xFF2B4052);
            folder(c,16,y+8,22);c.text(fit(loc.name(),172,1.5f),46,y+9,-1,1.5f);
        }
        var date=java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(java.time.ZoneId.systemDefault());
        for(int i=0;i<visibleRows()&&first+i<r.entries().size();i++) {
            int index=first+i,y=LIST_TOP+i*ROW_HEIGHT;var entry=r.entries().get(index);
            if(index==selected||index==hoverRow)c.rect(242,y,Math.max(1,pixelWidth()-262),32,.3f,index==selected?0xFF39566F:0xFF283B4D);
            if(entry.directory())folder(c,252,y+7,22);else PixelIcon.FILE_TEXT.draw(c,252,y+5,24,.45f,0xFFB6C9DC);
            c.text(fit(entry.name(),Math.max(1,(details()?modifiedX():pixelWidth()-20)-300),1.5f),284,y+9,-1,1.5f);
            if(details()){c.text(entry.modified()==0?"-":date.format(java.time.Instant.ofEpochMilli(entry.modified())),modifiedX(),y+10,0xFFB8CBDE,1.25f);
            c.text(entry.directory()?"Folder":entry.link()?"Link":"File",pixelWidth()-260,y+10,0xFFB8CBDE,1.25f);
            c.text(entry.directory()?"-":size(entry.size()),pixelWidth()-130,y+10,0xFFB8CBDE,1.25f);}
        }
        if(!r.error().isEmpty())c.text(r.error(),260,124,0xFFFFA5A5,1.5f);
        else if(r.entries().isEmpty())c.text("This folder is empty.",260,124,0xFFB8CBDE,1.5f);
        if(r.entries().size()>visibleRows()){float track=visibleRows()*(float)ROW_HEIGHT,h=Math.max(16,track*visibleRows()/r.entries().size());c.rect(pixelWidth()-14,LIST_TOP,5,track,.3f,0xFF283B4D);c.rect(pixelWidth()-14,LIST_TOP+(track-h)*first/(r.entries().size()-visibleRows()),5,h,.4f,0xFF8AA6BD);}
        c.text(fit(r.entries().size()+" items"+(r.truncated()?" (first 10,000 shown)":"")+" | Scroll to browse",pixelWidth()-32,1.25f),16,pixelHeight()-31,0xFF9BADBF,1.25f);
        if(pixelWidth()>900)c.text(fit(notice,pixelWidth()-490,1.25f),470,pixelHeight()-31,0xFFB8CBDE,1.25f);
    }
    private void colorButton(PanelCanvas c,PixelIcon icon,int x,int y,int width,int height,boolean enabled){
        int color=enabled?hoverColor(x,y,width,height,0xFF304457,0xFF426079):0xFF263542;
        c.rect(x,y,width,height,.3f,color);icon.draw(c,x+(width-24)/2f,y+10,24,.45f,enabled?-1:0xFF758393);
    }
}
