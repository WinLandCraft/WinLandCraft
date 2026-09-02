package dev.winlandcraft;
import dev.winlandcraft.api.v2.*;
import java.util.function.Consumer;

final class PluginGpuTransfer {
    static void copy(GpuSource source,Consumer<GpuFrame> copy){
        GpuFrame frame=source.acquire();
        if(frame!=null)try{copy.accept(frame);}finally{source.release(frame);}
    }
}
