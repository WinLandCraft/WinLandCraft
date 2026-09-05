package dev.winlandcraft;

import java.nio.file.*;
import java.util.*;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

/** Native tabbed editor. Drafts remain in memory when the window is closed. */
public final class NotepadPanel extends NativePanel {
    private final List<NoteDocument> tabs=new ArrayList<>();
    private NoteDocument active,pendingClose;
    private int firstTab;
    private boolean editing,selecting,pathSelected;
    private String savePath,message="Drop a file here from File Manager, or start typing.";
    private boolean error;
    public NotepadPanel(){super(3.2f,1.8f,1280,720);newTab();}
    int visibleRows(){return Math.max(1,(pixelHeight()-192)/22);}
    int visibleTabs(){return Math.max(1,(pixelWidth()-112)/190);}
    private int textWidth(){return Math.max(1,pixelWidth()-110);}
    private int dialogX(){return Math.max(8,(pixelWidth()-1000)/2);}
    private int dialogY(){return Math.max(88,(pixelHeight()-180)/2);}
    private int dialogWidth(){return Math.max(1,Math.min(1000,pixelWidth()-16));}
    @Override protected void layoutChanged(){firstTab=Math.clamp(firstTab,0,Math.max(0,tabs.size()-visibleTabs()));active.scroll=Math.clamp(active.scroll,0,Math.max(0,lines().length-visibleRows()));}
    @Override public boolean floatingControls(){return true;}
    @Override public String windowTitle(){return active.name()+(active.dirty()?" *":"")+" - Notepad";}
    @Override protected float minimumWidth(){return 1.6f;}
    @Override protected float minimumHeight(){return .9f;}
    @Override public void close(){super.close();editing=false;selecting=false;savePath=null;pendingClose=null;}
    @Override public boolean acceptsKeyboard(){return true;}
    @Override public boolean wantsKeyboard(){return editing||savePath!=null;}
    @Override public void keyboardStopped(){editing=false;selecting=false;}
    @Override public boolean acceptsFileDrop(){return true;}
    private void newTab(){if(tabs.size()>=32){fail("Close a tab first (32 tabs maximum).");return;}active=new NoteDocument();tabs.add(active);firstTab=Math.max(0,tabs.size()-visibleTabs());}
    private void fail(String text){message=text;error=true;}
    @Override public void dropFile(Path path) {
        if(savePath!=null||pendingClose!=null){fail("Finish the current dialog before opening a file.");return;}
        for(var tab:tabs)if(path.toAbsolutePath().normalize().equals(tab.path)){active=tab;firstTab=Math.max(0,tabs.indexOf(tab)-visibleTabs()+1);return;}
        if(tabs.size()>=32){fail("Close a tab first (32 tabs maximum).");return;}
        var tab=new NoteDocument();tab.path=path.toAbsolutePath().normalize();tab.busy=true;tabs.add(tab);active=tab;firstTab=Math.max(0,tabs.size()-visibleTabs());
        message="Opening "+path.getFileName()+"...";error=false;
        Thread.startVirtualThread(()->{
            try {
                var loaded=NoteFiles.load(path);
                Minecraft.getInstance().execute(()->{tab.path=loaded.path();tab.text=tab.saved=loaded.text();tab.charset=loaded.charset();tab.bom=loaded.bom();tab.newline=loaded.newline();tab.original=loaded.original();tab.busy=false;message="Opened "+tab.name();error=false;});
            }catch(Exception failure){Minecraft.getInstance().execute(()->{tab.path=null;tab.busy=false;fail("Could not open "+path.getFileName()+": "+reason(failure));});}
        });
    }
    private static String reason(Exception failure){if(failure instanceof AccessDeniedException||failure instanceof SecurityException)return "Access denied.";if(failure instanceof NoSuchFileException)return "File not found.";return failure.getMessage()==null?"File operation failed.":failure.getMessage();}
    private void save(boolean as) {
        if(active.busy)return;
        if(as||active.path==null){savePath=active.path==null?FileDirectory.home().resolve("Untitled.txt").toString():active.path.toString();pathSelected=true;editing=true;return;}
        write(active,active.path,active.original);
    }
    private void write(NoteDocument tab,Path target,byte[] expected) {
        if(tab.busy)return;tab.busy=true;String text=tab.text;message="Saving...";error=false;
        Thread.startVirtualThread(()->{
            try {
                byte[] data=NoteFiles.encode(text,tab.charset,tab.bom,tab.newline);NoteFiles.save(target,data,expected);
                Minecraft.getInstance().execute(()->{tab.path=target.toAbsolutePath().normalize();tab.original=data;tab.saved=text;tab.busy=false;savePath=null;message="Saved "+tab.name();error=false;if(pendingClose==tab&&!tab.dirty())remove(tab);});
            }catch(Exception failure){Minecraft.getInstance().execute(()->{tab.busy=false;fail("Could not save: "+reason(failure));});}
        });
    }
    private void remove(NoteDocument tab){tabs.remove(tab);pendingClose=null;if(tabs.isEmpty())newTab();else active=tabs.get(Math.min(tabs.size()-1,Math.max(0,tabs.indexOf(active))));firstTab=Math.clamp(firstTab,0,Math.max(0,tabs.size()-visibleTabs()));}
    private void closeTab(NoteDocument tab){if(tab.busy){fail("Wait for the file operation to finish.");return;}if(tab.dirty()){active=tab;pendingClose=tab;}else remove(tab);}
    @Override public void character(char ch,int modifiers) {
        if(savePath!=null){if(!Character.isISOControl(ch)){if(pathSelected){savePath="";pathSelected=false;}if(savePath.length()<4096)savePath+=ch;}return;}
        if(editing&&pendingClose==null&&!active.busy&&!Character.isISOControl(ch)){active.replace(String.valueOf(ch));reveal();}
    }
    @Override public void key(int key,int scan,int action,int modifiers) {
        if(action==GLFW.GLFW_RELEASE)return;boolean ctrl=(modifiers&(GLFW.GLFW_MOD_CONTROL|GLFW.GLFW_MOD_SUPER))!=0,shift=(modifiers&GLFW.GLFW_MOD_SHIFT)!=0;
        var clipboard=Minecraft.getInstance().keyboardHandler;
        if(savePath!=null) {
            if(ctrl&&key==GLFW.GLFW_KEY_A)pathSelected=true;
            else if(ctrl&&key==GLFW.GLFW_KEY_V){for(char ch:clipboard.getClipboard().toCharArray())character(ch,0);}
            else if(key==GLFW.GLFW_KEY_BACKSPACE){savePath=pathSelected?"":savePath.isEmpty()?"":savePath.substring(0,savePath.offsetByCodePoints(savePath.length(),-1));pathSelected=false;}
            else if(key==GLFW.GLFW_KEY_ENTER||key==GLFW.GLFW_KEY_KP_ENTER)savePath();
            return;
        }
        if(pendingClose!=null)return;
        if(ctrl&&key==GLFW.GLFW_KEY_S){save(shift);return;}
        if(ctrl&&key==GLFW.GLFW_KEY_N){newTab();return;}
        if(ctrl&&key==GLFW.GLFW_KEY_W){closeTab(active);return;}
        if(!editing||active.busy)return;
        if(ctrl&&key==GLFW.GLFW_KEY_A){active.anchor=0;active.caret=active.text.length();}
        else if(ctrl&&(key==GLFW.GLFW_KEY_C||key==GLFW.GLFW_KEY_X)){clipboard.setClipboard(active.selection());if(key==GLFW.GLFW_KEY_X)active.replace("");}
        else if(ctrl&&key==GLFW.GLFW_KEY_V)active.replace(clipboard.getClipboard());
        else if(ctrl&&key==GLFW.GLFW_KEY_Z)active.undo(shift);
        else if(ctrl&&key==GLFW.GLFW_KEY_Y)active.undo(true);
        else if(key==GLFW.GLFW_KEY_BACKSPACE)active.erase(true);
        else if(key==GLFW.GLFW_KEY_DELETE)active.erase(false);
        else if(key==GLFW.GLFW_KEY_ENTER||key==GLFW.GLFW_KEY_KP_ENTER)active.replace("\n");
        else if(key==GLFW.GLFW_KEY_TAB)active.replace("\t");
        else if(key==GLFW.GLFW_KEY_LEFT)active.horizontal(-1,shift);
        else if(key==GLFW.GLFW_KEY_RIGHT)active.horizontal(1,shift);
        else if(key==GLFW.GLFW_KEY_UP)active.vertical(-1,shift);
        else if(key==GLFW.GLFW_KEY_DOWN)active.vertical(1,shift);
        else if(key==GLFW.GLFW_KEY_HOME)active.move(ctrl?0:active.lineStart(),shift);
        else if(key==GLFW.GLFW_KEY_END)active.move(ctrl?active.text.length():active.lineEnd(),shift);
        reveal();
    }
    private void savePath(){try{Path path=Path.of(savePath);if(!path.isAbsolute())path=FileDirectory.home().resolve(path);write(active,path.normalize(),null);}catch(InvalidPathException invalid){fail("Invalid file path.");}}
    private String[] lines(){return active.lines();}
    private int caretLine(){int line=0;for(int i=0;i<active.caret;i++)if(active.text.charAt(i)=='\n')line++;return line;}
    private void reveal(){int line=caretLine();if(line<active.scroll)active.scroll=line;else if(line>=active.scroll+visibleRows())active.scroll=line-visibleRows()+1;int start=active.lineStart(),column=active.caret-start;if(column<active.columnScroll)active.columnScroll=column;else if(column>active.columnScroll+100)active.columnScroll=column-100;
        while(active.columnScroll<column&&Minecraft.getInstance().font.width(display(active.text.substring(start+active.columnScroll,active.caret)))*1.5f>Math.max(1,textWidth()-20))active.columnScroll++;
    }
    private static String display(String text){return text.replace("\t","    ");}
    private int locate(int x,int y) {
        var lines=lines();int row=Math.clamp((y-104)/22+active.scroll,0,lines.length-1),offset=0;
        for(int i=0;i<row;i++)offset+=lines[i].length()+1;
        String line=lines[row];int column=Math.min(active.columnScroll,line.length());float width=0,target=Math.max(0,(x-72)/1.5f);
        while(column<line.length()){int next=line.offsetByCodePoints(column,1);int size=Minecraft.getInstance().font.width(display(line.substring(column,next)));if(width+size/2f>target)break;width+=size;column=next;}
        return offset+column;
    }
    @Override public void mouseDown(int x,int y,int button) {
        if(button!=0||y<0)return;
        int dx=dialogX(),dy=dialogY(),dw=dialogWidth();
        if(savePath!=null){if(y>=dy+116&&y<dy+160){if(x>=dx+dw-260&&x<dx+dw-140)savePath();else if(x>=dx+dw-120&&x<dx+dw)savePath=null;}else if(x>=dx+24&&x<dx+dw-24&&y>=dy+56&&y<dy+100){editing=true;pathSelected=true;}return;}
        if(pendingClose!=null){if(y>=dy+116&&y<dy+160){if(x>=dx+dw-460&&x<dx+dw-320)save(false);else if(x>=dx+dw-300&&x<dx+dw-160)remove(pendingClose);else if(x>=dx+dw-140&&x<dx+dw)pendingClose=null;}return;}
        if(y<40){if(x>=pixelWidth()-100){newTab();return;}int index=(x-12)/190+firstTab;if(x>=12&&index<tabs.size()&&index<firstTab+visibleTabs()){var tab=tabs.get(index);if((x-12)%190>=164)closeTab(tab);else active=tab;}return;}
        if(y<84){if(x<90)newTab();else if(x<180)save(false);else if(x<290)save(true);else if(x<390&&!active.busy)active.undo(false);else if(x<490&&!active.busy)active.undo(true);return;}
        if(y>=104&&y<104+visibleRows()*22&&x>=68&&x<pixelWidth()-24&&!active.busy){editing=true;selecting=true;active.move(locate(x,y),false);}
    }
    @Override public void mouseUp(int x,int y,int button){if(button==0)selecting=false;}
    @Override public void hover(int x,int y){if(selecting){active.move(locate(x,y),true);}}
    @Override public void scroll(int x,int y,double amount){if(y<40)firstTab=Math.clamp(firstTab-(int)Math.signum(amount),0,Math.max(0,tabs.size()-visibleTabs()));else if(GLFW.glfwGetKey(Minecraft.getInstance().getWindow().getWindow(),GLFW.GLFW_KEY_LEFT_SHIFT)==GLFW.GLFW_PRESS)active.columnScroll=Math.max(0,active.columnScroll-(int)Math.signum(amount)*10);else active.scroll=Math.clamp(active.scroll-(int)Math.signum(amount)*3,0,Math.max(0,lines().length-visibleRows()));}
    private static String fit(String text,int width,float scale){return Minecraft.getInstance().font.plainSubstrByWidth(text,(int)(width/scale));}
    private void button(PanelCanvas c,PixelIcon icon,String text,int x,int y,int width){c.rect(x,y,width,44,.45f,hoverColor(x,y,width,44,0xFF344958,0xFF496176));icon.draw(c,x+10,y+10,24,.55f,-1);c.text(text,x+42,y+15,-1,1.5f);}
    private void toolbar(PanelCanvas c,PixelIcon icon,String text,int x,int width,boolean enabled){
        c.rect(x,40,width,44,.3f,enabled?hoverColor(x,40,width,44,0xFF1C2B36,0xFF30475A):0xFF18242F);
        icon.draw(c,x+8,50,24,.4f,enabled?-1:0xFF71818E);c.text(text,x+40,56,enabled?-1:0xFF71818E,1.5f);
    }
    @Override void drawSurface(PanelCanvas c) {
        c.rect(-3,-3,pixelWidth()+6,pixelHeight()+6,0,0xFF536579);
        c.rect(0,0,pixelWidth(),pixelHeight(),.1f,0xFF242424);c.rect(0,0,pixelWidth(),40,.2f,0xFF13212C);
        for(int i=0;i<visibleTabs()&&firstTab+i<tabs.size();i++){var tab=tabs.get(firstTab+i);int x=12+i*190;colorTab(c,tab,x);}
        c.rect(pixelWidth()-100,4,88,36,.3f,hoverColor(pixelWidth()-100,4,88,36,0xFF1C2B36,0xFF30475A));PixelIcon.PLUS.draw(c,pixelWidth()-68,10,24,.45f,-1);
        boolean controls=savePath==null&&pendingClose==null;
        toolbar(c,PixelIcon.PLUS,"New",0,90,controls);toolbar(c,PixelIcon.SAVE,"Save",90,90,controls&&!active.busy);
        toolbar(c,PixelIcon.COPY,"Save As",180,110,controls&&!active.busy);toolbar(c,PixelIcon.UNDO,"Undo",290,100,controls&&!active.busy);toolbar(c,PixelIcon.REDO,"Redo",390,100,controls&&!active.busy);
        c.rect(490,40,Math.max(0,pixelWidth()-490),44,.2f,0xFF1C2B36);
        var lines=lines();int offset=0;
        for(int row=0;savePath==null&&pendingClose==null&&row<Math.min(lines.length,active.scroll+visibleRows());row++) {
            String line=lines[row];if(row>=active.scroll){int y=104+(row-active.scroll)*22;int start=Math.min(active.columnScroll,line.length());String visible=fit(display(line.substring(start)),textWidth(),1.5f);
                c.text(Integer.toString(row+1),10,y,0xFF788C9D,1.25f);
                int lo=Math.clamp(active.low()-offset,start,line.length()),hi=Math.clamp(active.high()-offset,start,line.length());
                if(hi>lo){float left=Math.min(textWidth(),Minecraft.getInstance().font.width(display(line.substring(start,lo)))*1.5f),right=Math.min(textWidth(),Minecraft.getInstance().font.width(display(line.substring(start,hi)))*1.5f);c.rect(72+left,y-2,right-left,20,.3f,0xFF365F79);}
                c.text(visible,72,y,0xFFE6E6E6,1.5f);
                if(editing&&active.caret>=offset&&active.caret<=offset+line.length()&&(System.currentTimeMillis()/500)%2==0){int col=active.caret-offset;if(col>=start){float dx=Minecraft.getInstance().font.width(display(line.substring(start,col)))*1.5f;if(dx<textWidth())c.rect(72+dx,y-2,1.5f,19,.5f,0xFFF1F1F1);}}
            }offset+=line.length()+1;
        }
        c.text(fit(active.busy?"Working...":message,Math.max(1,pixelWidth()-45),1.25f),16,pixelHeight()-68,error?0xFFFFA5A5:0xFF91AABD,1.25f);
        c.rect(0,pixelHeight()-40,pixelWidth(),40,.2f,0xFF1C2B36);c.text("Ln "+(caretLine()+1)+", Col "+(active.caret-active.lineStart()+1)+"  |  "+active.text.length()+" characters",16,pixelHeight()-26,0xFFB8CBDE,1.25f);
        if(pixelWidth()>1050)c.text("Plain text | "+active.charset.name()+" | "+(active.newline.equals("\r\n")?"CRLF":active.newline.equals("\r")?"CR":"LF")+" | Shift+scroll: horizontal",pixelWidth()-580,pixelHeight()-26,0xFFB8CBDE,1.1f);
        if(savePath!=null||pendingClose!=null){int x=dialogX(),y=dialogY(),w=dialogWidth();c.rect(x,y,w,180,.4f,0xFF12232F);
            if(savePath!=null){c.text("Save As - enter a new file path",x+24,y+24,-1,1.5f);c.rect(x+24,y+56,w-48,44,.5f,0xFF334958);c.text(fit(savePath,Math.max(1,w-70),1.5f),x+36,y+70,pathSelected?0xFF72ECF1:-1,1.5f);button(c,PixelIcon.SAVE,"Save",x+w-260,y+116,120);button(c,PixelIcon.CLOSE,"Cancel",x+w-120,y+116,120);}
            else {c.text("This tab has unsaved changes.",x+24,y+44,-1,1.5f);button(c,PixelIcon.SAVE,"Save",x+w-460,y+116,140);button(c,PixelIcon.TRASH,"Discard",x+w-300,y+116,140);button(c,PixelIcon.CLOSE,"Cancel",x+w-140,y+116,140);}
        }
    }
    private void colorTab(PanelCanvas c,NoteDocument tab,int x){
        int color=tab==active?0xFF34414C:hoverColor(x,4,184,36,0xFF1C2B36,0xFF2B3E4D);
        c.rect(x,4,184,36,.3f,color);c.text(fit(tab.name()+(tab.dirty()?" *":""),143,1.4f),x+8,15,-1,1.4f);
        PixelIcon.CLOSE.draw(c,x+164,12,20,.45f,hovered(x+164,4,20,36)?-1:0xFFBDD1DF);
    }
}
