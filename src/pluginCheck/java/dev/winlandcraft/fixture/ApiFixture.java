package dev.winlandcraft.fixture;
import dev.winlandcraft.api.v1.*;
/** Compile-only ABI fixture, not an installable app or example project. */
public final class ApiFixture implements WinLandCraftPlugin {
    public void register(PluginRegistry registry) {
        for(var kind:AppKind.values())registry.register(AppDefinition.builder(
            "api_fixture:"+kind.name().toLowerCase(java.util.Locale.ROOT),kind.name(),kind,Probe::new)
            .extensions("wlctest").build());
    }
    private static final class Probe implements App { }
}
