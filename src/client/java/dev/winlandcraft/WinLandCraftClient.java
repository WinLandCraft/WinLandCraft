package dev.winlandcraft;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudLayerRegistrationCallback;
import net.fabricmc.fabric.api.client.rendering.v1.IdentifiedLayer;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class WinLandCraftClient implements ClientModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("winlandcraft");
    public static WindowControls controls;
    @Override public void onInitializeClient() {
        if(!net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("mcef"))
            throw new IllegalStateException("WinLandCraft clients require MCEF 2.1.6 for Minecraft 1.21.4. Dedicated servers do not need MCEF.");
        ModSettings.load();
        StreamAudio.install();
        WebApps.load();
        var give = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.winlandcraft.give_tasks",
                InputConstants.Type.KEYSYM, InputConstants.UNKNOWN.getValue(), "key.categories.winlandcraft"));
        var typing = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.winlandcraft.typing",
                InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_G, "key.categories.winlandcraft"));
        var apps = new AppWindows();
        var streams = new StreamClient(apps);
        streams.register();
        controls = new WindowControls(apps, typing);
        net.fabricmc.fabric.api.client.screen.v1.ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            if (screen instanceof net.minecraft.client.gui.screens.options.OptionsScreen) {
                net.fabricmc.fabric.api.client.screen.v1.Screens.getButtons(screen).add(
                        net.minecraft.client.gui.components.Button.builder(Component.literal("WinLandCraft..."), button ->
                                client.setScreen(new ModSettingsScreen(screen, () -> {
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
                                }))).bounds(8, 8, 120, 20).build());
            }
        });
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            controls.tick(client);
            apps.windows.forEach(window -> window.tick(client));
            streams.tick(client);
            while (give.consumeClick()) {
                if (client.player == null || client.screen != null || client.player.isSpectator() || controls.isTyping()) continue;
                if (ClientPlayNetworking.canSend(RequestTasksPayload.TYPE)) ClientPlayNetworking.send(RequestTasksPayload.INSTANCE);
                else client.player.displayClientMessage(Component.literal("Controls require a world running WinLandCraft."), true);
            }
        });
        UseItemCallback.EVENT.register(WinLandCraftClient::use);
        UseBlockCallback.EVENT.register((p, l, h, hit) -> use(p, l, h));
        UseEntityCallback.EVENT.register((p, l, h, entity, hit) -> use(p, l, h));
        WorldRenderEvents.AFTER_ENTITIES.register(context -> {
            controls.update(context.camera());
            apps.windows.forEach(window -> window.render(context));
            controls.renderHandles(context);
        });
        HudLayerRegistrationCallback.EVENT.register(layers -> layers.addLayer(IdentifiedLayer.of(
                ResourceLocation.fromNamespaceAndPath("winlandcraft", "interaction_hints"), (graphics, delta) -> {
                    var client = Minecraft.getInstance();
                    controls.renderResizeHint(graphics);
                    if (controls.isTyping() && !client.options.hideGui)
                        graphics.drawString(client.font, "Typing in window | Esc to return", 8, 8, 0xFF51CFDF, true);
                })));
        WorldRenderEvents.END.register(context -> streams.renderCapture());
        // Fabric can notify disconnect on Netty's IO thread. Input state and CEF/GL resources
        // belong to the client/render thread, including when disconnect races a world tick.
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(() -> {
            controls.cancel();
            streams.clear();
            apps.windows.forEach(WorldPanel::close);
        }));
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> { WebAppIconProbe.cancelCurrent(); controls.cancel(); streams.shutdown(); apps.windows.forEach(WorldPanel::close); });
        LOGGER.info("WinLandCraft browser window controls initialized.");
    }
    private static InteractionResult use(Player player, Level level, InteractionHand hand) {
        if (player.isSpectator() || !WinLandCraft.isControl(player.getItemInHand(hand))) return InteractionResult.PASS;
        if (level.isClientSide()) controls.use(Minecraft.getInstance(), hand);
        return InteractionResult.SUCCESS;
    }
}
