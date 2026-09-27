package dev.winlandcraft.api.v2;
public interface PluginRegistry {
    int API_VERSION = 2;
    void register(AppDefinition definition);
}
