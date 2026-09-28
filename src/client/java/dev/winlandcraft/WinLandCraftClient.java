package dev.winlandcraft;

import com.mojang.blaze3d.platform.InputConstants;
import dev.winlandcraft.mixin.OptionsKeyMappingsMixin;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import org.lwjgl.sdl.SDLScancode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class WinLandCraftClient implements ClientModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("winlandcraft");
    public static WindowControls controls;
    public static AppWindows apps;
    static StreamClient streams;
    /** True when the current server negotiated WinLandCraft channels. False on vanilla servers: no
     * control items are given, the Get-controls key opens Tasks directly, and gaze controls are forced. */
    public static volatile boolean serverHasMod = false;
    public static boolean clientSideOnly() { return !serverHasMod; }
    private static final KeyMapping.Category CATEGORY =
            KeyMapping.Category.register(Identifier.fromNamespaceAndPath("winlandcraft", "windows"));
    @Override public void onInitializeClient() {
        ModSettings.load();
        AdBlock.install();
        Thread.ofVirtual().name("WinLandCraft ffmpeg prewarm").start(Ffmpeg::prewarm);
        StreamAudio.install();
        WebApps.load();
        var give = registerKey(new KeyMapping("key.winlandcraft.give_tasks",
                InputConstants.UNKNOWN.getValue(), CATEGORY));
        var desktop = registerKey(new KeyMapping("key.winlandcraft.open_desktop",
                InputConstants.UNKNOWN.getValue(), CATEGORY));
        var typing = registerKey(new KeyMapping("key.winlandcraft.typing",
                SDLScancode.SDL_SCANCODE_G, CATEGORY));
        apps = new AppWindows();
        apps.plugins.load();
        apps.plugins.loadV2();
        ScreenLighting.install(apps);
        streams = new StreamClient(apps);
        controls = new WindowControls(apps, typing);
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            controls.tick(client);
            apps.windows.forEach(window -> window.tick(client));
            streams.tick(client);
            streams.renderCapture();
            while (give.consumeClick()) {
                if (client.player == null || client.gui.screen() != null || client.player.isSpectator() || controls.isTyping()) continue;
                if (StreamClient.canSend()) StreamClient.send(RequestTasksPayload.INSTANCE);
                else if (clientSideOnly() && client.level != null) {
                    apps.tasks.open(client);
                    client.player.sendOverlayMessage(Component.translatable("message.winlandcraft.tasks_opened"));
                } else client.player.sendOverlayMessage(Component.literal("Controls require a world running WinLandCraft."));
            }
            while (desktop.consumeClick()) {
                if (client.player == null || client.gui.screen() != null || client.level == null
                        || client.player.isSpectator() || controls.isTyping()) continue;
                client.gui.setScreen(new DesktopScreen(apps));
            }
        });
        UseItemCallback.EVENT.register(WinLandCraftClient::use);
        UseBlockCallback.EVENT.register((p, l, h, hit) -> use(p, l, h));
        UseEntityCallback.EVENT.register((p, l, h, entity, hit) -> use(p, l, h));
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> { WebAppIconProbe.cancelCurrent(); controls.cancel(); streams.shutdown(); apps.windows.forEach(WorldPanel::close);ScreenLighting.shutdown(); });
        LOGGER.info("WinLandCraft browser window controls initialized.");
    }

    /** Registers a keymapping like the old fabric helper: construct (self-registers) plus
     * append to the options array so it shows up in the controls screen. */
    private static KeyMapping registerKey(KeyMapping mapping) {
        var options = Minecraft.getInstance().options;
        var expanded = java.util.Arrays.copyOf(options.keyMappings, options.keyMappings.length + 1);
        expanded[expanded.length - 1] = mapping;
        ((OptionsKeyMappingsMixin) options).winlandcraft$setKeyMappings(expanded);
        return mapping;
    }

    /** Opens the settings screen. Called by the options-screen mixin. */
    public static void openSettings(net.minecraft.client.gui.screens.Screen parent) {
        var client = Minecraft.getInstance();
        client.gui.setScreen(new ModSettingsScreen(parent, () -> {
            controls.cancel();
            apps.reconcileWebApps();
            if (!ModSettings.freePanelRotation) {
                var adjusted = new java.util.HashSet<WorldPanel>();
                for (WorldPanel panel : apps.windows) if (panel.canInteract() && panel.isOpen() && adjusted.add(panel)) {
                    var oldRotation = new org.joml.Quaternionf(panel.orientation);
                    panel.orientation = PanelRotation.upright(panel.orientation);
                    WindowGroups.moved(panel, panel.position, oldRotation);
                    adjusted.addAll(WindowGroups.members(panel));
                }
                apps.syncAttachments();
            }
        }));
    }

    /** Runs disconnect teardown on the client thread. Called by the connection mixin. */
    public static void onDisconnect() {
        serverHasMod = false;
        if (controls == null) return;
        controls.cancel();
        streams.clear();
        apps.windows.forEach(WorldPanel::close);
    }

    /** Routes S2C relay payloads to the stream client. Called on the render thread by the dispatch mixin. */
    public static void deliverPayload(CustomPacketPayload payload) {
        if (streams != null) streams.receivePayload(payload);
    }

    private static InteractionResult use(Player player, Level level, InteractionHand hand) {
        var item=player.getItemInHand(hand);
        if (player.isSpectator() || !WinLandCraft.isControl(item)) return InteractionResult.PASS;
        if (level.isClientSide()) controls.use(Minecraft.getInstance(), hand);
        return item.is(WinLandCraft.LASER_POINTER)?InteractionResult.CONSUME:InteractionResult.SUCCESS;
    }
}
