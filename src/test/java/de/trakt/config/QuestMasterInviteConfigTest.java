package de.trakt.config;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * F6b: the questmaster invitation threshold is config surface —
 * {@code questmasters.inviteMastery} (default 2, clamped to 0..10, 0 = everyone).
 */
final class QuestMasterInviteConfigTest {

    @Test
    void defaultsInviteAtTwoMastery() {
        TraktServerConfig config = TraktServerConfig.defaults();
        assertEquals(1, config.questmasters().perVillage(), "F6a default unchanged");
        assertEquals(2, config.questmasters().inviteMastery(), "F6b default: two mastery levels");
    }

    @Test
    void fileWithoutInviteKeyFallsBackToDefault(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("trakt-server.properties");
        Files.writeString(file, """
                reset_timezone=AUTO
                questmasters.perVillage=2
                """);
        TraktServerConfig config = TraktServerConfig.load(file);
        assertEquals(2, config.questmasters().perVillage());
        assertEquals(2, config.questmasters().inviteMastery());
    }

    @Test
    void inviteMasteryIsConfigurableAndBounded(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("trakt-server.properties");
        Files.writeString(file, """
                questmasters.inviteMastery=0
                """);
        assertEquals(0, TraktServerConfig.load(file).questmasters().inviteMastery(),
                "0 disables the reputation gate — everyone may invite");

        Files.writeString(file, """
                questmasters.inviteMastery=7
                """);
        assertEquals(7, TraktServerConfig.load(file).questmasters().inviteMastery());

        // out-of-range values fall back to the default (the boundedInt contract shared
        // with questmasters.perVillage), not clamp — a typo never silently becomes 0
        Files.writeString(file, """
                questmasters.inviteMastery=99
                """);
        assertEquals(2, TraktServerConfig.load(file).questmasters().inviteMastery(),
                "values above 10 use the default");

        Files.writeString(file, """
                questmasters.inviteMastery=-3
                """);
        assertEquals(2, TraktServerConfig.load(file).questmasters().inviteMastery(),
                "negative values use the default");

        Files.writeString(file, """
                questmasters.inviteMastery=two
                """);
        assertEquals(2, TraktServerConfig.load(file).questmasters().inviteMastery(),
                "unparsable values use the default");
    }
}
