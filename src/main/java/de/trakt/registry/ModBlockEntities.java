package de.trakt.registry;

import de.trakt.Trakt;
import de.trakt.content.block.GuildWayshrineBlockEntity;
import java.util.Set;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.entity.BlockEntityType;

public final class ModBlockEntities {
    public static BlockEntityType<GuildWayshrineBlockEntity> GUILD_WAYSHRINE;

    private ModBlockEntities() {}

    public static void register() {
        GUILD_WAYSHRINE = Registry.register(
                BuiltInRegistries.BLOCK_ENTITY_TYPE,
                Identifier.fromNamespaceAndPath(Trakt.MOD_ID, "guild_wayshrine"),
                new BlockEntityType<>(GuildWayshrineBlockEntity::new, Set.of(ModBlocks.GUILD_WAYSHRINE))
        );
        Trakt.LOGGER.info("Registered block entities");
    }
}
