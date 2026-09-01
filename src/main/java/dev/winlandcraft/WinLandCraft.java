package dev.winlandcraft;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

public final class WinLandCraft implements ModInitializer {
    public static final ResourceLocation TASKS_ID = ResourceLocation.fromNamespaceAndPath("winlandcraft", "tasks");
    public static final Item TASKS = new Item(new Item.Properties()
            .setId(ResourceKey.create(Registries.ITEM, TASKS_ID)).stacksTo(1));
    public static final ResourceLocation DRAG_ID = ResourceLocation.fromNamespaceAndPath("winlandcraft", "window_drag");
    public static final Item WINDOW_DRAG = new Item(new Item.Properties()
            .setId(ResourceKey.create(Registries.ITEM, DRAG_ID)).stacksTo(1));
    // Read old inventories without an unknown-item registry error; never give this item again.
    private static final Item LEGACY_INTERACT = createItem("interact");
    public static final Item APPS = createItem("apps");
    public static final Item[] CONTROLS = {TASKS, WINDOW_DRAG, APPS};

    private static Item createItem(String name) {
        return new Item(new Item.Properties().setId(ResourceKey.create(Registries.ITEM,
                ResourceLocation.fromNamespaceAndPath("winlandcraft", name))).stacksTo(1));
    }

    public static boolean isControl(ItemStack stack) {
        for (Item item : CONTROLS) if (stack.is(item)) return true;
        return false;
    }

    @Override
    public void onInitialize() {
        StreamRelay.register();
        Registry.register(BuiltInRegistries.ITEM, TASKS_ID, TASKS);
        Registry.register(BuiltInRegistries.ITEM, DRAG_ID, WINDOW_DRAG);
        Registry.register(BuiltInRegistries.ITEM, ResourceLocation.fromNamespaceAndPath("winlandcraft", "interact"), LEGACY_INTERACT);
        Registry.register(BuiltInRegistries.ITEM, ResourceLocation.fromNamespaceAndPath("winlandcraft", "apps"), APPS);
        PayloadTypeRegistry.playC2S().register(RequestTasksPayload.TYPE, RequestTasksPayload.CODEC);
        net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> removeLegacyInteract(handler.player));
        ServerPlayNetworking.registerGlobalReceiver(RequestTasksPayload.TYPE, (payload, context) -> {
            var player = context.player();
            removeLegacyInteract(player);
            if (player.isSpectator() || !player.isAlive()) {
                return;
            }
            boolean added = false;
            boolean full = false;
            for (Item control : CONTROLS) {
                boolean owned = player.containerMenu.getCarried().is(control);
                for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
                    owned |= player.getInventory().getItem(slot).is(control);
                }
                if (owned) {
                    continue;
                }
                if (player.getInventory().getFreeSlot() == -1) {
                    full = true;
                } else {
                    added |= player.getInventory().add(new ItemStack(control));
                }
            }
            player.containerMenu.broadcastChanges();
            player.displayClientMessage(Component.translatable(full ? "message.winlandcraft.inventory_full"
                    : added ? "message.winlandcraft.controls_given" : "message.winlandcraft.already_have_controls"), true);
        });
    }
    private static void removeLegacyInteract(net.minecraft.server.level.ServerPlayer player) {
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++)
            if (player.getInventory().getItem(slot).is(LEGACY_INTERACT)) player.getInventory().setItem(slot, ItemStack.EMPTY);
        if (player.containerMenu.getCarried().is(LEGACY_INTERACT)) player.containerMenu.setCarried(ItemStack.EMPTY);
        player.containerMenu.broadcastChanges();
    }
}
