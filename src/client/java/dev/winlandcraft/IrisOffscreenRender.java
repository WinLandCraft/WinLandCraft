package dev.winlandcraft;

import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.loader.api.FabricLoader;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

/** Keeps Iris's world pipeline out of WinLandCraft's private composition target. */
final class IrisOffscreenRender {
    private static final Scope NOOP=()->{};
    private static final Access ACCESS=findAccess();
    private static boolean announced;

    private IrisOffscreenRender(){}

    static Scope enter() {
        RenderSystem.assertOnRenderThread();
        if(ACCESS==null)return NOOP;
        boolean bypass=(boolean)ACCESS.bypass.get();
        boolean renderingLevel=(boolean)ACCESS.renderingLevel.get();
        ACCESS.bypass.set(true);
        ACCESS.renderingLevel.set(false);
        if(!announced) {
            announced=true;
            WinLandCraftClient.LOGGER.info("Isolating off-screen panel composition from the active Iris world pipeline");
        }
        return ()->{
            ACCESS.renderingLevel.set(renderingLevel);
            ACCESS.bypass.set(bypass);
        };
    }

    private static Access findAccess() {
        if(!FabricLoader.getInstance().isModLoaded("iris"))return null;
        try {
            Class<?> state=Class.forName("net.irisshaders.iris.vertices.ImmediateState",false,
                    IrisOffscreenRender.class.getClassLoader());
            var lookup=MethodHandles.publicLookup();
            return new Access(lookup.findStaticVarHandle(state,"bypass",boolean.class),
                    lookup.findStaticVarHandle(state,"isRenderingLevel",boolean.class));
        } catch (ReflectiveOperationException | LinkageError failure) {
            WinLandCraftClient.LOGGER.warn("Iris off-screen rendering compatibility is unavailable",failure);
            return null;
        }
    }

    @FunctionalInterface interface Scope extends AutoCloseable {
        @Override void close();
    }

    private record Access(VarHandle bypass,VarHandle renderingLevel){}
}
