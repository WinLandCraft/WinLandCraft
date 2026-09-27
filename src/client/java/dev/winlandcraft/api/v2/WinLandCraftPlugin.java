package dev.winlandcraft.api.v2;
/** Fabric entrypoint named "winlandcraft:plugins_v2". Called once on the client. */
@FunctionalInterface
public interface WinLandCraftPlugin { void register(PluginRegistry registry); }
