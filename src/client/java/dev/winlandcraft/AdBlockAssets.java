package dev.winlandcraft;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

final class AdBlockAssets {
    private static final String REVISION = "2b7ebab7bd59c4d1a470adb5a6624b35a7d8236a";
    private static final URI BASE = URI.create("https://raw.githubusercontent.com/brave/adblock-rust/"
            + REVISION + "/data/brave/");
    private static final Asset MAIN = new Asset("brave-main-list.txt",
            "44aed8fd4b2329e274af3b78e406a1c1ccffb0b8bebfa3e1ad2fc9c7547de54d", 8L * 1024 * 1024);
    private static final Asset UNBREAK = new Asset("brave-unbreak.txt",
            "a9777da81f959ce3f77c6677ad7d93d9a03f3d0041876db6a4bf4b6c2564f7a2", 128L * 1024);
    private static final Asset RESOURCES = new Asset("brave-resources.json",
            "de6eb441a4ba2823d2cc67beabae0a3e0b6862cc56ca8586af6bac654c45f3fd", 2L * 1024 * 1024);

    private AdBlockAssets() {}

    static Bundle load() throws IOException, InterruptedException {
        Path directory = FabricLoader.getInstance().getConfigDir().resolve("winlandcraft/adblock/" + REVISION);
        return new Bundle(read(directory, MAIN), read(directory, UNBREAK), read(directory, RESOURCES));
    }

    private static byte[] read(Path directory, Asset asset) throws IOException, InterruptedException {
        Path file = VerifiedDownload.fetch(directory, asset.name, BASE.resolve(asset.name), asset.sha256, asset.maximumBytes);
        return Files.readAllBytes(file);
    }

    record Bundle(byte[] main, byte[] unbreak, byte[] resources) {}
    private record Asset(String name, String sha256, long maximumBytes) {}
}
