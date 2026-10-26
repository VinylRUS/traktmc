package de.trakt.resource;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import de.trakt.access.ForgingQuickMoveState;
import org.junit.jupiter.api.Test;

final class MixinPackageBoundaryTest {
    @Test
    void runtimeContractsStayOutsideTheMixinOwnedPackage() {
        String contractPackage = ForgingQuickMoveState.class.getPackageName();

        assertFalse(contractPackage.equals("de.trakt.mixin")
                        || contractPackage.startsWith("de.trakt.mixin."),
                "A class referenced by transformed targets cannot live in the configured Mixin package");
        assertThrows(ClassNotFoundException.class,
                () -> Class.forName("de.trakt.mixin.ForgingQuickMoveState"));
    }
}
