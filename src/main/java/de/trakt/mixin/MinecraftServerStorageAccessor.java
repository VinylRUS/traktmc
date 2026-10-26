package de.trakt.mixin;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelStorageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes the level storage accessor so the quest definition registry can read and write
 * the per-world folder {@code <world>/trakt/quests/} (RELEASE_PLAN §4 F1, D9).
 */
@Mixin(MinecraftServer.class)
public interface MinecraftServerStorageAccessor {

    @Accessor("storageSource")
    LevelStorageSource.LevelStorageAccess trakt$storageSource();
}
