package dev.winlandcraft.api.v1;
public interface PluginRegistry {
    int API_VERSION = 1;
    void register(AppDefinition definition);
}
