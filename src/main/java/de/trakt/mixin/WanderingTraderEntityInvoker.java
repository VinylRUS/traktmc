package de.trakt.mixin;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.wanderingtrader.WanderingTrader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(WanderingTrader.class)
public interface WanderingTraderEntityInvoker {
    @Invoker("updateTrades")
    void trakt$invokeFillRecipes(ServerLevel world);
}
