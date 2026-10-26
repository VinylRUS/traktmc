package de.trakt.reputation;

import static org.junit.jupiter.api.Assertions.*;
import de.trakt.config.TraktServerConfig;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ReputationConfigTest {
    @TempDir Path temporary;
    private TraktServerConfig load(String content) throws Exception {
        Path path = temporary.resolve("server.properties"); Files.writeString(path, content);
        Method load = TraktServerConfig.class.getDeclaredMethod("load", Path.class); load.setAccessible(true);
        return (TraktServerConfig) load.invoke(null, path);
    }
    @Test void existingConfigReceivesTheApprovedSocialDefaults() throws Exception {
        var config = load("daily_reset_hour=8\n");
        assertTrue(config.socialReputation().enabled()); assertFalse(config.socialReputation().trackCreative());
        assertTrue(config.socialReputation().caravanMortality()); assertEquals(8, config.dailyResetHour());
    }
    @Test void switchesAreIndependentAndMalformedValuesUseTheirOwnDefaults() throws Exception {
        var config = load("socialReputation.enabled=false\nsocialReputation.trackCreative=true\nsocialReputation.caravanMortality=false\n");
        assertFalse(config.socialReputation().enabled()); assertTrue(config.socialReputation().trackCreative());
        assertFalse(config.socialReputation().caravanMortality());
        var invalid = load("socialReputation.enabled=maybe\nsocialReputation.trackCreative=maybe\n");
        assertTrue(invalid.socialReputation().enabled()); assertFalse(invalid.socialReputation().trackCreative());
    }
}
