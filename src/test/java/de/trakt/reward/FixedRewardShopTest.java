package de.trakt.reward;
import static org.junit.jupiter.api.Assertions.*;
import de.trakt.shop.ShopService;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.*;
final class FixedRewardShopTest {
    @BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        var components=net.minecraft.core.component.DataComponentMap.builder().set(net.minecraft.core.component.DataComponents.MAX_STACK_SIZE,64).build();
        for(var item:net.minecraft.core.registries.BuiltInRegistries.ITEM) item.builtInRegistryHolder().bindComponents(components);
    }
    @Test void allSixFixedShopPricesMatchDraftWithoutOldMultiplier() {
        // D7: prices are stored in copper waycoins (x100 of the authored silver figures).
        assertEquals(1200, ShopService.offer("proviantbeutel").price());
        assertEquals(1400, ShopService.offer("hunters_satchel").price());
        assertEquals(2000, ShopService.offer("nether_supplies").price());
        assertEquals(3000, ShopService.offer("light_supplies").price());
        assertEquals(3200, ShopService.offer("rail_supplies").price());
        assertEquals(3200, ShopService.offer("leather_supplies").price());
    }
    @Test void evenMaximumDiscountCostsMoreThanMaterialAndLocalDeliveryValue() {
        for(var entry : RewardShopCatalog.all().entrySet()) {
            int value=0;
            for(var goods : entry.getValue().goods()) value += de.trakt.economy.DeliveryEconomy.materialValue(net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(net.minecraft.resources.Identifier.withDefaultNamespace(goods.item())),goods.count());
            long cheapest=(entry.getValue().price()*85+99)/100;
            assertTrue(cheapest > value + de.trakt.economy.CurrencyService.silver(2L),entry.getKey());
        }
    }
    @Test void bundlesAreHiddenAndUnbuyableUntilPersonalProgressUnlocksThem() throws Exception {
        var world=org.mockito.Mockito.mock(net.minecraft.server.level.ServerLevel.class);
        var server=org.mockito.Mockito.mock(net.minecraft.server.MinecraftServer.class);
        var player=org.mockito.Mockito.mock(net.minecraft.server.level.ServerPlayer.class);
        var id=java.util.UUID.randomUUID(); org.mockito.Mockito.when(world.getServer()).thenReturn(server);org.mockito.Mockito.when(player.getUUID()).thenReturn(id);
        var load=de.trakt.data.QuestState.class.getDeclaredMethod("fromNbt",net.minecraft.nbt.CompoundTag.class);load.setAccessible(true);
        var state=(de.trakt.data.QuestState)load.invoke(null,new net.minecraft.nbt.CompoundTag());
        try(var lookup=org.mockito.Mockito.mockStatic(de.trakt.data.QuestState.class);var reputation=org.mockito.Mockito.mockStatic(de.trakt.reputation.ReputationService.class,org.mockito.Mockito.CALLS_REAL_METHODS)) {
            lookup.when(() -> de.trakt.data.QuestState.get(server)).thenReturn(state);
            for(var track:de.trakt.reputation.ReputationService.ReputationTrack.values()) reputation.when(() -> de.trakt.reputation.ReputationService.get(world,id,track)).thenReturn(0);
            for(String offer:RewardShopCatalog.all().keySet()) { assertFalse(ShopService.isOfferUnlocked(world,id,offer),offer);assertFalse(ShopService.availableOfferIds(world,id).contains(offer));assertEquals(0,ShopService.buy(world,player,offer)); }
            var projects=java.util.Map.of("nether_supplies",de.trakt.quest.story.VillageProjectType.WATCH_BELL,"rail_supplies",de.trakt.quest.story.VillageProjectType.FORGE_CHARTER,"leather_supplies",de.trakt.quest.story.VillageProjectType.PASTURE_CHARTER);
            for(var e:projects.entrySet()) { state.getPlayerData(id).setUnlockedProject(e.getValue().id(),true);assertTrue(ShopService.isOfferUnlocked(world,id,e.getKey())); }
            var tracks=java.util.Map.of("proviantbeutel",de.trakt.reputation.ReputationService.ReputationTrack.FARMING,"light_supplies",de.trakt.reputation.ReputationService.ReputationTrack.CRAFTING,"hunters_satchel",de.trakt.reputation.ReputationService.ReputationTrack.MONSTER_HUNTING);
            for(var e:tracks.entrySet()) { reputation.when(() -> de.trakt.reputation.ReputationService.get(world,id,e.getValue())).thenReturn(24);assertFalse(ShopService.isOfferUnlocked(world,id,e.getKey()));reputation.when(() -> de.trakt.reputation.ReputationService.get(world,id,e.getValue())).thenReturn(25);assertTrue(ShopService.isOfferUnlocked(world,id,e.getKey())); }
        }
    }
}
