package fr.robie.minimessageflow.compat.adventure4;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.Style;
import net.kyori.adventure.text.format.TextColor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The Adventure 4 bridge on the Adventure the tests run on ({@code -PadventureVersion}): used on 4.x, refused on 5.x.
 */
class Adventure4BridgeTest {

    private static final String VERSION = System.getProperty("minimessageflow.test.adventureVersion", "4.17.0");
    private static final boolean ON_4 = VERSION.startsWith("4.");

    private final Adventure4Bridge bridge = new Adventure4Bridge();

    @Test
    @DisplayName("Supports Adventure 4.x only")
    void runtime() {
        assertEquals(4, this.bridge.major());
        assertEquals(ON_4, this.bridge.supportsRuntime(), "on Adventure " + VERSION);
    }

    @Test
    @DisplayName("Shadow colours and virtual components are detected from 4.18")
    void features() {
        if (!ON_4) {
            return;
        }
        boolean from418 = Integer.parseInt(VERSION.split("[.-]")[1]) >= 18;
        assertEquals(from418, this.bridge.hasShadowColor(), VERSION);
        assertEquals(from418, this.bridge.hasVirtualComponents(), VERSION);
        assertFalse(this.bridge.isVirtual(Component.text("x")));
    }

    @Test
    @DisplayName("What 4.17 can't name stays with MiniMessage; change_page keeps its text")
    void styles() {
        if (!ON_4) {
            return;
        }
        assertNull(this.bridge.shadow(TextColor.color(0xff0000), 128));
        assertNull(this.bridge.sprite(null, "minecraft:stone"));
        assertNull(this.bridge.head("Steve"));
        assertNull(this.bridge.clickCustom("a:b", null));
        Style page = this.bridge.clickChangePage("3");
        assertNotNull(page);
        assertNotNull(page.clickEvent());
    }
}
