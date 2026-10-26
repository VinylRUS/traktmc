package de.trakt.registry;

import de.trakt.Trakt;
import java.util.function.Supplier;
import net.fabricmc.fabric.api.creativetab.v1.FabricCreativeModeTab;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

public final class ModCreativeTabs {
    public static final ResourceKey<CreativeModeTab> TRAKT = ResourceKey.create(
            Registries.CREATIVE_MODE_TAB,
            Identifier.fromNamespaceAndPath(Trakt.MOD_ID, "trakt"));

    private ModCreativeTabs() {}

    public static void register() {
        Registry.register(BuiltInRegistries.CREATIVE_MODE_TAB, TRAKT,
                FabricCreativeModeTab.builder()
                        .title(Component.translatable("itemGroup.trakt"))
                        .icon(ModCreativeTabs::journalIcon)
                        .displayItems((parameters, output) -> {
                            for (Entry entry : Entry.values()) {
                                output.accept(entry.item());
                            }
                        })
                        .build());

        Trakt.LOGGER.info("Registered Trakt creative tab");
    }

    private static ItemStack journalIcon() {
        ItemStack icon = new ItemStack(ModItems.CARAVAN_LEDGER);
        icon.set(DataComponents.ITEM_MODEL,
                Identifier.fromNamespaceAndPath(Trakt.MOD_ID, "journal_inventory_button"));
        return icon;
    }

    enum Entry {
        SILVERMARK(() -> ModItems.SILVERMARK),
        CROWN(() -> ModItems.CROWN),
        MAGIC_SHARD(() -> ModItems.MAGIC_SHARD),

        CARAVAN_LEDGER(() -> ModItems.CARAVAN_LEDGER),
        SURVEYORS_COMPASS(() -> ModItems.SURVEYORS_COMPASS),
        STARREACH_RING(() -> ModItems.STARREACH_RING),
        MERCHANT_SEAL(() -> ModItems.MERCHANT_SEAL),
        SHEPHERD_FLUTE(() -> ModItems.SHEPHERD_FLUTE),
        APIARISTS_SMOKER(() -> ModItems.APIARISTS_SMOKER),
        ROADWARDEN_HORN(() -> ModItems.ROADWARDEN_HORN),

        APIARY_CHARTER_PLAQUE(() -> ModItems.APIARY_CHARTER_PLAQUE),
        VILLAGE_LEDGER_PLAQUE(() -> ModItems.VILLAGE_LEDGER_PLAQUE),
        FORGE_CHARTER_PLAQUE(() -> ModItems.FORGE_CHARTER_PLAQUE),
        MARKET_CHARTER_PLAQUE(() -> ModItems.MARKET_CHARTER_PLAQUE),
        PASTURE_CHARTER_PLAQUE(() -> ModItems.PASTURE_CHARTER_PLAQUE),
        WATCH_BELL_RELIQUARY(() -> ModItems.WATCH_BELL_RELIQUARY),

        CARTOGRAPHERS_LENS(() -> ModItems.CARTOGRAPHERS_LENS),
        CRACKED_SHRINE_CORE(() -> ModItems.CRACKED_SHRINE_CORE),
        RESTORED_SHRINE_CORE(() -> ModItems.RESTORED_SHRINE_CORE),
        WAYFARERS_SIGIL(() -> ModItems.WAYFARERS_SIGIL),
        GUILD_WAYSHRINE(() -> ModItems.GUILD_WAYSHRINE),
        GUILD_NOTICE_POST(() -> ModItems.GUILD_NOTICE_POST),
        EMBERGLASS_LANTERN(() -> ModItems.EMBERGLASS_LANTERN),
        GUILD_MILESTONE(() -> ModItems.GUILD_MILESTONE),
        GUILD_COURIERS_SATCHEL(() -> ModItems.GUILD_COURIERS_SATCHEL),
        RELAY_POST(() -> ModItems.RELAY_POST),
        QUEST_BOARD(() -> ModItems.QUEST_BOARD);

        private final Supplier<Item> item;

        Entry(Supplier<Item> item) {
            this.item = item;
        }

        Item item() {
            return item.get();
        }
    }
}
