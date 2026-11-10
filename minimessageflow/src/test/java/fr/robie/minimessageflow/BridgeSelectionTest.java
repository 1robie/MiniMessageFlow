package fr.robie.minimessageflow;

import fr.robie.minimessageflow.core.AdventureBridge;
import fr.robie.minimessageflow.core.AdventureBridges;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * With every bridge in the jar, the one of the Adventure this test runs on ({@code -PadventureVersion}) is chosen.
 */
class BridgeSelectionTest {

    private static final String VERSION = System.getProperty("minimessageflow.test.adventureVersion", "4.17.0");

    private static int part(int index) {
        return Integer.parseInt(VERSION.split("[.-]")[index]);
    }

    @Test
    @DisplayName("The bridge of the running Adventure major is chosen")
    void chosenByMajor() {
        AdventureBridge bridge = AdventureBridges.current();
        assertEquals(part(0), bridge.major(), bridge.describe() + " on Adventure " + VERSION);
    }

    @Test
    @DisplayName("Shadow colours and virtual components exist from Adventure 4.18")
    void features() {
        AdventureBridge bridge = AdventureBridges.current();
        boolean from418 = part(0) > 4 || part(1) >= 18;
        assertEquals(from418, bridge.hasShadowColor(), bridge.describe() + " on Adventure " + VERSION);
        assertEquals(from418, bridge.hasVirtualComponents(), bridge.describe() + " on Adventure " + VERSION);
    }
}
