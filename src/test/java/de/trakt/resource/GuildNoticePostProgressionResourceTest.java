package de.trakt.resource;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class GuildNoticePostProgressionResourceTest {
    @Test
    void replacementRecipeRequiresProgressionGatedMarketCharterPlaque() throws Exception {
        String recipe = Files.readString(Path.of("src", "main", "resources", "data", "trakt",
                "recipe", "guild_notice_post.json"));

        assertTrue(recipe.contains("trakt:market_charter_plaque"));
        assertFalse(recipe.contains("minecraft:gold_nugget"));
    }

    @Test
    void recipeBookHintUsesTheSameProgressionGate() throws Exception {
        String source = Files.readString(Path.of("src", "main", "java", "de", "trakt", "recipe",
                "TraktRecipeBookService.java"));

        int recipe = source.indexOf("recipe(\"guild_notice_post\"");
        int nextRecipe = source.indexOf("recipe(\"guild_wayshrine\"", recipe);
        String noticePostUnlock = source.substring(recipe, nextRecipe);
        assertTrue(noticePostUnlock.contains("ModItems.MARKET_CHARTER_PLAQUE"));
        assertFalse(noticePostUnlock.contains("Items.PAPER"));
    }

    @Test
    void placedBoardsStillDropThemselvesForSafeRecovery() throws Exception {
        String loot = Files.readString(Path.of("src", "main", "resources", "data", "trakt",
                "loot_table", "blocks", "guild_notice_post.json"));

        assertTrue(loot.contains("trakt:guild_notice_post"));
    }

    @Test
    void firstCommissionStillAwardsThePersonalGuildWaymarker() throws Exception {
        String service = Files.readString(Path.of("src", "main", "java", "de", "trakt", "guildtown",
                "GuildTownService.java"));

        assertTrue(service.contains("grantReward(data, \"guild_waymarker\")"));
        assertTrue(service.contains("giveNamed(player, ModItems.GUILD_NOTICE_POST"));
    }
}
