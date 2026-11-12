package fr.robie.minimessageflow.compat.adventure5;

import fr.robie.minimessageflow.core.AdventureBridge;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.Style;
import net.kyori.adventure.text.format.TextColor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The Adventure 5 bridge on the Adventure the tests run on ({@code -PadventureVersion}): used on 5.x; on 4.x it is
 * refused, or does not even link.
 */
class Adventure5BridgeTest {

    private static final String VERSION = System.getProperty("minimessageflow.test.adventureVersion", "4.17.0");
    private static final boolean ON_5 = VERSION.startsWith("5.");

    /**
     * The bridge, or null where its class does not link (an Adventure without the types it names).
     */
    private static AdventureBridge bridge() {
        try {
            return new Adventure5Bridge();
        } catch (LinkageError e) {
            return null;
        }
    }

    @Test
    @DisplayName("Supports Adventure 5.x only")
    void runtime() {
        AdventureBridge bridge = bridge();
        if (bridge == null) {
            assertFalse(ON_5, "the bridge must link on Adventure " + VERSION);
            return;
        }
        assertEquals(5, bridge.major());
        assertEquals(ON_5, bridge.supportsRuntime(), "on Adventure " + VERSION);
    }

    @Test
    @DisplayName("Builds shadows, sprites, heads and clicks; an invalid key or page gives null")
    void styles() {
        if (!ON_5) {
            return;
        }
        AdventureBridge bridge = bridge();
        assertNotNull(bridge);
        assertTrue(bridge.hasShadowColor());
        assertTrue(bridge.hasVirtualComponents());
        assertFalse(bridge.isVirtual(Component.text("x")));

        Style shadow = bridge.shadow(TextColor.color(0xff0000), 128);
        assertNotNull(shadow);
        assertFalse(shadow.isEmpty());
        assertNotNull(bridge.shadow(null, 0));

        assertNotNull(bridge.sprite(null, "minecraft:stone"));
        assertNotNull(bridge.sprite("minecraft:blocks", "minecraft:stone"));
        assertNull(bridge.sprite(null, "Bad Key"));
        assertNotNull(bridge.head(null));
        assertNotNull(bridge.head("Steve"));

        Style page = bridge.clickChangePage("3");
        assertNotNull(page);
        assertNotNull(page.clickEvent());
        assertNull(bridge.clickChangePage("three"));
        Style custom = bridge.clickCustom("a:b", null);
        assertNotNull(custom);
        assertNotNull(custom.clickEvent());
        assertNull(bridge.clickCustom("Bad Key", null));
    }
}
