package de.trakt.config;

import java.time.DayOfWeek;
import java.time.ZoneId;

public final class QuestConfig {
    private QuestConfig() {}

    public static ZoneId resetZone() {
        return TraktServerConfig.get().resetZone();
    }

    public static int dailyResetHour() {
        return TraktServerConfig.get().dailyResetHour();
    }

    public static DayOfWeek weeklyResetDay() {
        return TraktServerConfig.get().weeklyResetDay();
    }

    public static int weeklyResetHour() {
        return TraktServerConfig.get().weeklyResetHour();
    }
}
