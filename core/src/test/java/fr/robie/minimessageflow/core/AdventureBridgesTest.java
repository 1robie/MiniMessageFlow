package fr.robie.minimessageflow.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AdventureBridgesTest {

    @Test
    @DisplayName("Bridge names follow the package of core, newest first")
    void namesFollowPackage() {
        assertEquals(List.of(
                "fr.robie.minimessageflow.compat.adventure5.Adventure5Bridge",
                "fr.robie.minimessageflow.compat.adventure4.Adventure4Bridge"
        ), AdventureBridges.bridgeClassNames());
    }

    @Test
    @DisplayName("Without any bridge on the class path, the error says what was tried")
    void noBridge() {
        // core's own tests have no compat module on their class path.
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> AdventureBridges.select(AdventureBridgesTest.class.getClassLoader()));
        assertTrue(error.getMessage().contains("Adventure5Bridge (not on the class path)"), error.getMessage());
        assertTrue(error.getMessage().contains("Adventure4Bridge (not on the class path)"), error.getMessage());
    }
}
