package de.trakt;

import org.junit.jupiter.api.Assumptions;

/**
 * Gates resource-dependent tests so they skip gracefully in code-only checkouts
 * (the public repository mirror ships neither the ARR textures/lang prose nor
 * the authored structure templates — see ASSET_LIST.md). In a full development
 * checkout every resource is present and the tests run unchanged.
 */
public final class TestResourceGate {
    private TestResourceGate() {
    }

    public static void require(String... paths) {
        ClassLoader loader = TestResourceGate.class.getClassLoader();
        for (String path : paths) {
            Assumptions.assumeTrue(loader.getResource(path) != null,
                    "resource not present in this checkout: " + path);
        }
    }
}
