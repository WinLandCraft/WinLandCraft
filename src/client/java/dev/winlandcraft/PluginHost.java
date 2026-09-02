package dev.winlandcraft;

import dev.winlandcraft.api.v1.*;
import java.nio.file.Path;
import java.util.*;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

final class PluginHost {
    private final AppWindows apps;
    private final Map<String,PluginPanel> launchers=new LinkedHashMap<>();
    private final List<PluginPanel> extra=new ArrayList<>();
    PluginHost(AppWindows apps){this.apps=apps;}
    void load(){
        for(var entry:FabricLoader.getInstance().getEntrypointContainers("winlandcraft:plugins",WinLandCraftPlugin.class)) {
            String provider=entry.getProvider().getMetadata().getId();
            try {register(provider,entry.getEntrypoint());}
            catch(RuntimeException|LinkageError|AssertionError failure){WinLandCraftClient.LOGGER.error("Could not load WinLandCraft plugin {}",provider,failure);}
        }
    }
    void register(String provider,WinLandCraftPlugin plugin){
        var staged=new LinkedHashMap<String,AppDefinition>();
        boolean[] open={true};
        try {plugin.register(definition->{
            if(!open[0])throw new IllegalStateException("Registration has ended");
            Objects.requireNonNull(definition);
            if(!definition.id().startsWith(provider+":"))throw new IllegalArgumentException("App namespace must match plugin mod id: "+provider);
            if(staged.size()>=64||launchers.size()+staged.size()>=512)throw new IllegalStateException("Too many app registrations");
            if(launchers.containsKey(definition.id())||staged.putIfAbsent(definition.id(),definition)!=null)throw new IllegalArgumentException("Duplicate app id: "+definition.id());
        });}finally{open[0]=false;}
        staged.values().forEach(definition->{var panel=new PluginPanel(definition);launchers.put(definition.id(),panel);apps.windows.add(panel);});
    }
    List<AppWindows.AppEntry> entries(){return launchers.values().stream().map(p->new AppWindows.AppEntry(p.definition.name(),p,null)).toList();}
    List<AppWindows.AppEntry> running(){return extra.stream().filter(WorldPanel::isOpen).map(p->new AppWindows.AppEntry(p.windowTitle(),p,null)).toList();}
    List<AppWindows.FileTarget> handlers(Path path){return launchers.values().stream().filter(p->p.definition.accepts(path)).sorted(Comparator.comparing(p->p.definition.id())).map(p->new AppWindows.FileTarget(p.definition.id(),p.definition.name())).toList();}
    boolean openFile(String id,FileManagerPanel source,Path path,FileAppPlacement.Side side){
        var launcher=launchers.get(id);if(launcher==null||!launcher.definition.accepts(path)||!source.isOpen())return false;
        var panel=extra.stream().filter(p->!p.isOpen()&&p.definition.id().equals(id)).findFirst().orElse(null);
        if(panel==null){if(extra.size()>=64)return false;panel=new PluginPanel(launcher.definition);extra.add(panel);apps.windows.add(panel);}
        FileAppPlacement.place(source,panel,side,Minecraft.getInstance().gameRenderer.getMainCamera().getPosition());panel.dropFile(path);return true;
    }
}
