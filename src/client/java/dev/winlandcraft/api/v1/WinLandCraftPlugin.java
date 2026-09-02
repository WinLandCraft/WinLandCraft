package dev.winlandcraft.api.v1;
/** Fabric entrypoint named "winlandcraft:plugins". Called once on the client. */
@FunctionalInterface
public interface WinLandCraftPlugin { void register(PluginRegistry registry); }
