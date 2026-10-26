package de.trakt;

import com.google.common.collect.HashMultimap;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;
import de.trakt.commands.AdminCommands;
import de.trakt.commands.QuestCommands;
import de.trakt.api.ApiService;
import de.trakt.config.TraktServerConfig;
import de.trakt.network.QuestNetworking;
import de.trakt.quest.QuestService;
import de.trakt.registry.ModBlocks;
import de.trakt.registry.ModBlockEntities;
import de.trakt.registry.ModCreativeTabs;
import de.trakt.registry.ModEntities;
import de.trakt.registry.ModItems;
import de.trakt.registry.ModRecipeBookCategories;
import net.fabricmc.api.ModInitializer;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.component.ResolvableProfile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

public class Trakt implements ModInitializer {
    public static final String MOD_ID = "trakt";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    private static final String HONIGFASS_TEXTURE = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvZjFkYTM4YjFiYzc1YTM5ZTcxYzdkY2IzY2FiNzNjMjZjODI2ZjgyNmM1OWU4YTQ0MTRjMThjNDU1Y2VlOWE2MiJ9fX0=";
    private static final String ALT_REWARD_TEXTURE = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvMmM2ODAxNDlhZDE3ZTQ2ZmJiZjc2MDZiMjg0Y2M4M2EwM2IxYTY3Y2Q4YTUyNzE3YjQ0YmZhM2FkNTkxNGYxNCJ9fX0=";

    @Override
    public void onInitialize() {
        LOGGER.info("Initializing {}", MOD_ID);
        TraktServerConfig.bootstrap();
        ModRecipeBookCategories.register();
        ModBlocks.register();
        ModBlockEntities.register();
        ModItems.register();
        ModCreativeTabs.register();
        ModEntities.register();
        QuestService.registerEvents();
        QuestNetworking.register();
        QuestCommands.register();
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(de.trakt.reward.VanillaRewardService::onTick);
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(de.trakt.questmaster.QuestMasterSettlementService::onServerTick);
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            // attach the level storage, then pull the world folder layer into the registry
            de.trakt.quest.definition.QuestDefinitionRegistry.get().attachServer(server);
            var report = de.trakt.quest.definition.QuestDefinitionRegistry.get().reloadFromDisk();
            LOGGER.info("[quests] world layer attached: {} active definition(s) ({} builtin, {} datapack, {} world, {} api)",
                    report.total(), report.builtin(), report.datapack(), report.world(), report.api());
            de.trakt.quest.definition.QuestDefinitionRegistry.get().emitChangeEvent();
        });
        AdminCommands.register();
        ApiService.register();
        de.trakt.quest.definition.QuestDefinitionReloadListener.register();
    }

    public static ItemStack createHoneyBarrelHead() {
        return createDecorHead("honey_barrel", "item.trakt.honey_barrel", "item.trakt.honey_barrel.lore", HONIGFASS_TEXTURE);
    }

    public static ItemStack createAltRewardHead() {
        return createDecorHead("honeycomb_relic", "item.trakt.honeycomb_relic", "item.trakt.honeycomb_relic.lore", ALT_REWARD_TEXTURE);
    }

    public static ItemStack createDecorHead(String profileName, String titleKey, String textureValue) {
        return createDecorHead(profileName, titleKey, null, textureValue);
    }

    public static ItemStack createDecorHead(String profileName, String titleKey, String loreKey, String textureValue) {
        ItemStack stack = new ItemStack(Items.PLAYER_HEAD);
        var backing = HashMultimap.<String, Property>create();
        backing.put("textures", new Property("textures", textureValue));
        PropertyMap properties = new PropertyMap(backing);
        UUID profileId = UUID.nameUUIDFromBytes((MOD_ID + ":" + profileName).getBytes(StandardCharsets.UTF_8));
        GameProfile profile = new GameProfile(profileId, packetSafeProfileName(profileName, profileId), properties);
        stack.set(DataComponents.PROFILE, ResolvableProfile.createResolved(profile));
        stack.set(DataComponents.CUSTOM_NAME, Component.translatable(titleKey).withStyle(ChatFormatting.GREEN));
        if (loreKey != null && !loreKey.isBlank()) {
            stack.set(DataComponents.LORE, new ItemLore(List.of(
                    Component.translatable(loreKey).withStyle(ChatFormatting.DARK_GRAY)
            )));
        }
        return stack;
    }

    private static String packetSafeProfileName(String profileName, UUID profileId) {
        if (profileName != null && !profileName.isBlank() && profileName.length() <= 16) {
            return profileName;
        }
        String compactId = profileId.toString().replace("-", "");
        return "trakt_" + compactId.substring(0, 10);
    }
}
