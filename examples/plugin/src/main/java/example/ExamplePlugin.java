package example;
import dev.winlandcraft.api.v1.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;

public final class ExamplePlugin implements WinLandCraftPlugin {
    public void register(PluginRegistry registry) {
        registry.register(AppDefinition.builder("wlc_example:notes","Example Notes",AppKind.NATIVE,Notes::new).extensions("wlcnotes").build());
        registry.register(AppDefinition.builder("wlc_example:web","Example Web",AppKind.CHROMIUM,Web::new).build());
        registry.register(AppDefinition.builder("wlc_example:hybrid","Example Hybrid",AppKind.HYBRID,Hybrid::new).build());
    }
    public static final class Notes implements App {
        private WindowContext window;private String text="Click here and type. Esc returns to Minecraft.";
        public void onOpen(WindowContext window){this.window=window;}
        public void render(Canvas canvas){canvas.rectangle(0,0,canvas.width(),canvas.height(),0xFF202A32);canvas.text(text,20,30,0xFFFFFFFF,1.5f);}
        public void onPointerDown(int x,int y,int button){if(button==0)window.requestKeyboard(true);}
        public void onCharacter(char c,int modifiers){if(!Character.isISOControl(c)&&text.length()<8192)text+=c;}
        public void onKey(int key,int scan,int action,int modifiers){if(key==259&&action!=0&&!text.isEmpty())text=text.substring(0,text.offsetByCodePoints(text.length(),-1));}
        public void openFile(Path path){
            var session=window;
            Thread.startVirtualThread(()->{
                String value;
                try(var input=Files.newInputStream(path)){
                    byte[] bytes=input.readNBytes(8193);if(bytes.length>8192)throw new java.io.IOException("Example limit: 8 KiB");
                    value=new String(bytes,StandardCharsets.UTF_8);
                }catch(Exception error){value="Could not open file: "+error.getMessage();}
                String result=value;session.execute(()->{text=result;session.title(path.getFileName()+" - Example Notes");});
            });
        }
    }
    public static final class Web implements App {
        public void onOpen(WindowContext window){window.browser().navigate("https://example.com/");}
    }
    public static final class Hybrid implements App {
        private WindowContext window;
        public void onOpen(WindowContext window){this.window=window;window.browser().navigate("https://example.com/");}
        public void onResize(int width,int height){window.browser().bounds(0,52,width,Math.max(1,height-52));}
        public void render(Canvas canvas){canvas.rectangle(0,0,canvas.width(),52,0xFF254052);canvas.text("Reload page",14,18,0xFFFFFFFF,1.5f);}
        public void onPointerDown(int x,int y,int button){if(button==0&&y<52)window.browser().reload();}
        public void onBrowserTitleChanged(String title){window.title(title+" - Example Hybrid");}
    }
}
