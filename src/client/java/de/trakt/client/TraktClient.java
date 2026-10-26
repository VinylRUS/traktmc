package de.trakt.client;

import de.trakt.client.compat.ClientModCompat;
import de.trakt.client.config.TraktClientConfig;
import de.trakt.client.network.ClientQuestNetworking;
import de.trakt.client.hud.QuestTrackerHud;
import de.trakt.client.hud.TradeRouteMinimapHud;
import de.trakt.client.render.CaravanMerchantEntityRenderer;
import de.trakt.client.render.PilgrimEntityRenderer;
import de.trakt.client.render.QuestMasterEntityRenderer;
import de.trakt.client.render.TraitorEntityRenderer;
import de.trakt.client.render.WayshrineCrystalRenderer;
import de.trakt.client.ui.InventoryJournalTutorialState;
import de.trakt.registry.ModEntities;
import de.trakt.registry.ModBlockEntities;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.BlockEntityRendererRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.ModelLayerRegistry;
import net.minecraft.client.renderer.entity.EntityRenderers;
import net.minecraft.client.renderer.entity.DonkeyRenderer;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.resources.model.EquipmentClientInfo;

public class TraktClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        TraktClientConfig.bootstrap();
        ClientModCompat.bootstrap();
        ModelLayerRegistry.registerModelLayer(
                PilgrimEntityRenderer.PILGRIM_LAYER,
                PilgrimEntityRenderer::createModelData
        );
        ModelLayerRegistry.registerModelLayer(
                QuestMasterEntityRenderer.QUEST_MASTER_LAYER,
                QuestMasterEntityRenderer::createModelData
        );
        ModelLayerRegistry.registerModelLayer(
                CaravanMerchantEntityRenderer.CARAVAN_MERCHANT_LAYER,
                CaravanMerchantEntityRenderer::createModelData
        );
        ModelLayerRegistry.registerModelLayer(
                TraitorEntityRenderer.TRAITOR_LAYER,
                TraitorEntityRenderer::createModelData
        );
        EntityRenderers.register(ModEntities.PILGRIM, PilgrimEntityRenderer::new);
        EntityRenderers.register(ModEntities.QUEST_MASTER, QuestMasterEntityRenderer::new);
        EntityRenderers.register(ModEntities.CARAVAN_MERCHANT, CaravanMerchantEntityRenderer::new);
        EntityRenderers.register(ModEntities.CARAVAN_PACK_MULE, context -> new DonkeyRenderer<>(
                context, EquipmentClientInfo.LayerType.MULE_SADDLE, ModelLayers.MULE_SADDLE,
                DonkeyRenderer.Type.MULE, DonkeyRenderer.Type.MULE_BABY));
        EntityRenderers.register(ModEntities.TRAITOR, TraitorEntityRenderer::new);
        BlockEntityRendererRegistry.register(ModBlockEntities.GUILD_WAYSHRINE, WayshrineCrystalRenderer::new);
        ClientQuestNetworking.register();
        QuestTrackerHud.register();
        TradeRouteMinimapHud.register();
        InventoryJournalTutorialState.bootstrap();
    }
}
