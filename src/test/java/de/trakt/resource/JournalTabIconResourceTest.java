package de.trakt.resource;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.List;
import org.junit.jupiter.api.Test;

final class JournalTabIconResourceTest {
    @Test
    void everyJournalTabIconIsPackaged() {        de.trakt.TestResourceGate.require("assets/trakt/textures/gui/ui/icon_home.png");

        ClassLoader resources = JournalTabIconResourceTest.class.getClassLoader();
        for (String icon : List.of("home", "quests", "social", "guide", "story")) {
            String path = "assets/trakt/textures/gui/ui/icon_" + icon + ".png";
            assertNotNull(resources.getResource(path), () -> "Missing Journal tab icon: " + path);
        }
    }
}
