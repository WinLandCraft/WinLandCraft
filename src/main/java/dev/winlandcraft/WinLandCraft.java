package dev.winlandcraft;

import net.fabricmc.api.ModInitializer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;

public final class WinLandCraft implements ModInitializer {
    // Vanilla-client joins forbid custom registry entries: every synced
    // winlandcraft-namespaced entry makes Fabric tell vanilla players the mod is
    // required. Controls are therefore inert vanilla stand-ins (echo/amethyst
    // shards) carrying our item model, display name, and marker, so modded clients
    // render the real models while everyone else can still join and play.
    public static final Identifier TASKS_MODEL = Identifier.fromNamespaceAndPath("winlandcraft", "tasks");
    public static final Identifier LASER_MODEL = Identifier.fromNamespaceAndPath("winlandcraft", "laser_pointer");

    public static ItemStack tasksStack() {
        return controlStack(new ItemStack(Items.ECHO_SHARD), TASKS_MODEL, "item.winlandcraft.tasks", "tasks");
    }

    public static ItemStack laserStack() {
        return controlStack(new ItemStack(Items.AMETHYST_SHARD), LASER_MODEL, "item.winlandcraft.laser_pointer", "laser");
    }

    private static ItemStack controlStack(ItemStack stack, Identifier model, String nameKey, String marker) {
        stack.set(DataComponents.ITEM_MODEL, model);
        stack.set(DataComponents.CUSTOM_NAME, Component.translatable(nameKey).withStyle(style -> style.withItalic(false)));
        var tag = new CompoundTag();
        tag.putString("winlandcraft", marker);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        return stack;
    }

    public static boolean isTasks(ItemStack stack) {
        return stack.is(Items.ECHO_SHARD) && TASKS_MODEL.equals(stack.get(DataComponents.ITEM_MODEL));
    }

    public static boolean isLaser(ItemStack stack) {
        return stack.is(Items.AMETHYST_SHARD) && LASER_MODEL.equals(stack.get(DataComponents.ITEM_MODEL));
    }

    public static boolean isControl(ItemStack stack) {
        return isTasks(stack) || isLaser(stack);
    }

    @Override
    public void onInitialize() {
        StreamRelay.register();
    }

    /** Called on the server thread by the payload dispatch mixin. */
    public static void receiveTasks(net.minecraft.server.level.ServerPlayer player) {
        if (player.isSpectator() || !player.isAlive()) {
            return;
        }
        boolean added = false;
        boolean full = false;
        for (ItemStack control : new ItemStack[]{tasksStack(), laserStack()}) {
            if (owns(player, control)) {
                continue;
            }
            if (player.getInventory().getFreeSlot() == -1) {
                full = true;
            } else {
                added |= player.getInventory().add(control);
            }
        }
        player.containerMenu.broadcastChanges();
        player.sendSystemMessage(Component.translatable(full ? "message.winlandcraft.inventory_full"
                : added ? "message.winlandcraft.controls_given" : "message.winlandcraft.already_have_controls"), true);
    }

    private static boolean owns(net.minecraft.server.level.ServerPlayer player, ItemStack probe) {
        if (sameControl(player.containerMenu.getCarried(), probe)) {
            return true;
        }
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            if (sameControl(player.getInventory().getItem(slot), probe)) {
                return true;
            }
        }
        return false;
    }

    private static boolean sameControl(ItemStack stack, ItemStack probe) {
        if (!isControl(stack)) {
            return false;
        }
        return isTasks(stack) == isTasks(probe);
    }
}
