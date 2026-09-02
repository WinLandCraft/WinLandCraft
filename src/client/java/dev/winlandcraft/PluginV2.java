package dev.winlandcraft;
import dev.winlandcraft.api.v1.*;
import java.nio.file.Path;
/** Version adapter, leaving both public interfaces independent of implementation classes. */
final class PluginV2 {
    static AppDefinition adapt(dev.winlandcraft.api.v2.AppDefinition source){
        AppKind kind=switch(source.kind()){case NATIVE,SURFACE->AppKind.NATIVE;case CHROMIUM->AppKind.CHROMIUM;case HYBRID->AppKind.HYBRID;};
        var builder=AppDefinition.builder(source.id(),source.name(),kind,()->new Bridge(source.create())).size(source.width(),source.height())
                .extensions(source.extensions().toArray(String[]::new)).fileNames(source.fileNames().toArray(String[]::new));
        if(source.icon()!=null)builder.icon(source.icon());return builder.build();
    }
    private record Bridge(dev.winlandcraft.api.v2.App app) implements App {
        public void onOpen(WindowContext c){app.onOpen((dev.winlandcraft.api.v2.WindowContext)c);}
        public void onClose(){app.onClose();}public void onResize(int w,int h){app.onResize(w,h);}public void onTick(){app.onTick();}
        public void render(Canvas c){app.render((dev.winlandcraft.api.v2.Canvas)c);}
        public void onPointerMove(int x,int y){app.onPointerMove(x,y);}public void onPointerDown(int x,int y,int b){app.onPointerDown(x,y,b);}public void onPointerUp(int x,int y,int b){app.onPointerUp(x,y,b);}
        public void onScroll(int x,int y,double amount){app.onScroll(x,y,amount);}public void onKey(int k,int s,int a,int m){app.onKey(k,s,a,m);}public void onCharacter(char c,int m){app.onCharacter(c,m);}public void onFocusChanged(boolean f){app.onFocusChanged(f);}
        public void openFile(Path p){app.openFile(p);}public Path dragFileAt(int x,int y){return app.dragFileAt(x,y);}
        public void onBrowserLoaded(){app.onBrowserLoaded();}public void onBrowserAddressChanged(String s){app.onBrowserAddressChanged(s);}public void onBrowserTitleChanged(String s){app.onBrowserTitleChanged(s);}
    }
}
