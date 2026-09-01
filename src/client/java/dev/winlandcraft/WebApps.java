package dev.winlandcraft;

import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.mojang.blaze3d.platform.NativeImage;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import java.net.URI;
import java.nio.file.*;
import java.util.*;

/** Small, persistent app definitions. PNG icons are stored with the definition. */
final class WebApps {
    record App(String id, String name, String url, String iconPng) { }
    private static List<App> entries = List.of();
    private static final Map<String, ResourceLocation> textures = new HashMap<>();
    private static final Set<String> attempted = new HashSet<>();
    static List<App> all() { return entries; }
    static App find(String id) { return entries.stream().filter(a -> a.id.equals(id)).findFirst().orElse(null); }
    private static Path path() { return FabricLoader.getInstance().getConfigDir().resolve("winlandcraft-webapps.json"); }
    static String normalizeUrl(String text) {
        String url = text.strip();
        if (url.contains("://") && !url.matches("(?i)^https?://.*")) return null;
        if (!url.matches("(?i)^https?://.*")) url = "https://" + url;
        try {
            URI uri = URI.create(url);
            if (uri.getHost() == null || uri.getUserInfo() != null || !(uri.getScheme().equalsIgnoreCase("https") || uri.getScheme().equalsIgnoreCase("http"))) return null;
            return url;
        } catch (IllegalArgumentException e) { return null; }
    }
    static List<App> decode(String json) {
        List<App> parsed = new GsonBuilder().create().fromJson(json, new TypeToken<List<App>>() { }.getType());
        if (parsed == null) return List.of();
        Set<String> ids = new HashSet<>();
        List<App> valid = new ArrayList<>();
        for (App app : parsed) {
            if (app == null || app.id == null || app.name == null || app.name.isBlank() || app.name.length() > 48
                    || app.url == null || app.url.length() > 8192 || normalizeUrl(app.url) == null) continue;
            try { UUID.fromString(app.id); } catch (IllegalArgumentException e) { continue; }
            if (!ids.add(app.id)) continue;
            String icon = app.iconPng != null && app.iconPng.length() < 65536 ? app.iconPng : "";
            valid.add(new App(app.id, app.name, normalizeUrl(app.url), icon));
        }
        return List.copyOf(valid);
    }
    static String encode(List<App> apps) { return new GsonBuilder().setPrettyPrinting().create().toJson(apps); }
    static void load() {
        try { entries = Files.exists(path()) ? decode(Files.readString(path())) : List.of(); }
        catch (Exception e) { WinLandCraftClient.LOGGER.warn("Could not read saved webapps", e); }
    }
    static boolean put(App app) {
        var next = new ArrayList<>(entries);
        int index = -1;
        for (int i = 0; i < next.size(); i++) if (next.get(i).id.equals(app.id)) index = i;
        if (index < 0) next.add(app); else next.set(index, app);
        return commit(next, app.id);
    }
    static boolean remove(String id) {
        return commit(entries.stream().filter(a -> !a.id.equals(id)).toList(), id);
    }
    private static boolean commit(List<App> next, String changedId) {
        try {
            Files.createDirectories(path().getParent());
            Path temp = path().resolveSibling(path().getFileName() + ".tmp");
            Files.writeString(temp, encode(next));
            try { Files.move(temp, path(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temp, path(), StandardCopyOption.REPLACE_EXISTING); }
            entries = List.copyOf(next);
            ResourceLocation texture = textures.remove(changedId);
            if (texture != null) Minecraft.getInstance().getTextureManager().release(texture);
            attempted.remove(changedId);
            return true;
        } catch (Exception e) { WinLandCraftClient.LOGGER.error("Could not save webapps", e); return false; }
    }
    static ResourceLocation icon(App app) {
        if (app == null) return null;
        if (attempted.add(app.id) && app.iconPng != null && !app.iconPng.isBlank()) {
            try {
                byte[] bytes = Base64.getDecoder().decode(app.iconPng);
                // Saved icons are exactly 32x32; validate before handing bytes to the native decoder.
                var decoded = FaviconLoader.decode(bytes);
                if (decoded == null || decoded.getWidth() != 32 || decoded.getHeight() != 32) return null;
                var texture = ResourceLocation.fromNamespaceAndPath("winlandcraft", "app_icon_" + app.id);
                Minecraft.getInstance().getTextureManager().register(texture, new DynamicTexture(NativeImage.read(bytes)));
                textures.put(app.id, texture);
            } catch (Exception e) { WinLandCraftClient.LOGGER.debug("Could not read app icon", e); }
        }
        return textures.get(app.id);
    }
}
