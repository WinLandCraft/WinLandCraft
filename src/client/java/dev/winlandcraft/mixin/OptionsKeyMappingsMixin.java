package dev.winlandcraft.mixin;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Options;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Appends mod keymappings to the final options array. Replaces the fabric keybinding helper. */
@Mixin(Options.class)
public interface OptionsKeyMappingsMixin {
    @Mutable
    @Accessor("keyMappings")
    void winlandcraft$setKeyMappings(KeyMapping[] mappings);
}
