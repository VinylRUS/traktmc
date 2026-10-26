package de.trakt.caravan;

import de.trakt.caravan.TradeRouteGeometry.RoutePoint;
import de.trakt.data.PlayerQuestData;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class TradeGuildEconomyDistanceTest {
    @Test
    void verticalProfileChangesTraversalButNotGuildDistanceReward() {
        PlayerQuestData flat = route(0);
        PlayerQuestData mountain = route(100);

        assertTrue(TradeRouteService.routeTraversalDistanceBlocks(mountain, 0)
                > TradeRouteService.routeTraversalDistanceBlocks(flat, 0));
        assertEquals(TradeRouteService.routeEconomyDistanceBlocks(flat, 0),
                TradeRouteService.routeEconomyDistanceBlocks(mountain, 0));
        // D8: distance feeds the transport fee, not a full-price multiplier.
        assertEquals(de.trakt.economy.DeliveryEconomy.transportBonus(
                        TradeRouteService.routeEconomyDistanceBlocks(flat, 0), false, false, false),
                de.trakt.economy.DeliveryEconomy.transportBonus(
                        TradeRouteService.routeEconomyDistanceBlocks(mountain, 0), false, false, false));
    }

    private static PlayerQuestData route(int waypointY) {
        PlayerQuestData data = new PlayerQuestData();
        data.setTradeRouteInt("home_x", 0);
        data.setTradeRouteInt("home_z", 0);
        data.setTradeRouteInt("route_0_x", 100);
        data.setTradeRouteInt("route_0_z", 0);
        TradeRouteSurveyData.markRouteGeometryV2(data, 0, 0, 0);
        TradeRouteSurveyData.setRouteWaypoints(data, 0, List.of(new RoutePoint(50, waypointY, 0)));
        return data;
    }
}
