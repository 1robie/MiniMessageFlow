package fr.robie.minimessageflow;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

class PlaceholderValueTest {

    @Test
    @DisplayName("A null supplier, function or component is refused when the value is made")
    void nullsRefused() throws Exception {
        assertThrows(NullPointerException.class, () -> PlaceholderValue.ofDynamic(null));
        assertThrows(NullPointerException.class, () -> PlaceholderValue.ofViewer(null));
        assertThrows(NullPointerException.class, () -> PlaceholderValue.ofComponent(null));
        assertThrows(NullPointerException.class, () -> PlaceholderValue.ofDynamicComponent(null));
        assertThrows(NullPointerException.class, () -> PlaceholderValue.ofViewerComponent(null));
        assertThrows(NullPointerException.class, () -> Placeholder.of("item", (Component) null));
        assertThrows(NullPointerException.class, () -> Placeholder.builder().register("x", (Supplier<String>) null));
        assertThrows(NullPointerException.class,
                () -> Placeholder.builder().registerViewerComponent("x", (Function<net.kyori.adventure.pointer.Pointered, Component>) null));
        // Null text is still empty text, as documented.
        assertEquals("", PlaceholderValue.ofStatic(null).evaluate(null));
    }

    @Test
    @DisplayName("In parse, a component keeps its colours alone and gives its plain text to a modifier")
    void componentInParse() {
        AtomicInteger calls = new AtomicInteger();
        Placeholder p = Placeholder.ofComponent("item", () -> {
            calls.incrementAndGet();
            return Component.text("Sword", NamedTextColor.GOLD);
        });
        assertEquals("§6Sword 5 SWORD Swo", p.parse("%item% %item.length% %item.upper% %item.max:3%"));
        assertEquals(2, calls.get(), "once for its legacy text, once for its plain text");
    }

    @Test
    @DisplayName("A component's legacy text keeps a hex colour, in Bukkit's form")
    void hexColours() throws Exception {
        String text = PlaceholderValue.ofComponent(Component.text("x", TextColor.color(0xff8800))).evaluate(null);
        assertEquals("§x§f§f§8§8§0§0x", text);
        assertEquals("§cx", PlaceholderValue.ofComponent(Component.text("x", NamedTextColor.RED)).evaluate(null));
    }

    @Test
    @DisplayName("Without a viewer, a viewer value is empty text and its function is not called")
    void noViewer() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        PlaceholderValue value = PlaceholderValue.ofViewer(viewer -> {
            calls.incrementAndGet();
            return "x";
        });
        assertEquals("", value.evaluate(null));
        assertEquals(0, calls.get());
    }
}
