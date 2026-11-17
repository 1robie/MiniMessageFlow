package fr.robie.minimessageflow;

import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

class PlaceholderFixesTest {

    @Test
    @DisplayName("A null key is refused where it is given, not at a later render")
    void nullKeys() {
        assertThrows(NullPointerException.class, () -> Placeholder.of(null, "x"));
        assertThrows(NullPointerException.class, () -> Placeholder.of(null, (Supplier<String>) () -> "x"));
        assertThrows(NullPointerException.class, () -> Placeholder.of(null, Component.text("x")));
        assertThrows(NullPointerException.class, () -> Placeholder.ofComponent(null, () -> Component.text("x")));
        assertThrows(NullPointerException.class, () -> Placeholder.of("a", "1", null, "2"));
        assertThrows(NullPointerException.class, () -> Placeholder.of(null, "1", "b", "2"));
        assertThrows(NullPointerException.class, () -> Placeholder.builder().register(null, "x"));
    }

    @Test
    @DisplayName("A set of more than 8 values keeps its values when the builder is used again")
    void largeSetIsItsOwn() {
        Placeholder.Builder builder = Placeholder.builder();
        for (int i = 0; i < 12; i++) {
            builder.register("k" + i, "v" + i);
        }
        Placeholder first = builder.build();
        builder.register("k0", "changed").register("k99", "new");
        Placeholder second = builder.build();
        assertEquals("v0", first.parse("%k0%"));
        assertNull(first.get("k99"));
        assertEquals("changed new", second.parse("%k0% %k99%"));
        assertThrows(UnsupportedOperationException.class, () -> first.getMap().put("x", PlaceholderValue.ofStatic("y")));
    }

    @Test
    @DisplayName("parse with a ModifierSet reads modifiers as that set does: its number style")
    void parseWithModifiers() {
        Placeholder p = Placeholder.of("p", "1234.5");
        assertEquals("1,234.50", p.parse("%p.comma%"));
        assertEquals("1,234.50", p.parse("%p.comma%", null, ModifierSet.shared()));
        ModifierSet european = ModifierSet.builder().numberStyle(NumberStyle.EUROPEAN).add("shout", (v, a) -> v + "!").build();
        assertEquals("1.234,50", Placeholder.of("p", "1234,5").parse("%p.comma%", null, european));
        assertEquals("1234.5!", p.parse("%p.shout%", null, european));
        assertEquals("%p.shout%", p.parse("%p.shout%"), "an own modifier is not a shared one");
    }

    @Test
    @DisplayName("A value that fails is asked once per parse, and its tokens are kept")
    void failingValueOnce() {
        AtomicInteger calls = new AtomicInteger();
        Placeholder p = Placeholder.of("x", (Supplier<String>) () -> {
            calls.incrementAndGet();
            throw new IllegalStateException("down");
        });
        assertEquals("%x% %x% %x.upper%", p.parse("%x% %x% %x.upper%"));
        assertEquals(1, calls.get());
    }

    @Test
    @DisplayName("isViewerDependent: a text value never needs the viewer, a viewer value does")
    void viewerDependent() {
        Placeholder p = Placeholder.builder().register("name", "Steve")
                .register("hp", viewer -> "20").build();
        assertFalse(p.isViewerDependent("%name% %name.upper%"));
        assertTrue(p.isViewerDependent("%name% %hp.int%"));
        assertFalse(p.isViewerDependent("%missing%"));
    }

    @Test
    @DisplayName("toString shows the keys and their kinds, never the values")
    void toStringHidesValues() {
        Placeholder p = Placeholder.builder()
                .register("name", "SecretSteve")
                .register("time", () -> "x")
                .register("rank", viewer -> "x")
                .register("item", Component.text("SecretItem"))
                .registerComponent("hand", () -> Component.text("x"))
                .registerViewerComponent("head", viewer -> Component.text("x"))
                .build();
        String text = p.toString();
        assertEquals("Placeholder{name=text, time=dynamic, rank=viewer, item=component, hand=dynamic component, "
                + "head=viewer component}", text);
        assertFalse(text.contains("Secret"));
        assertEquals("Placeholder{}", Placeholder.empty().toString());
        assertTrue(Placeholder.of(Map.of("a", "1", "b", "2", "c", "3", "d", "4", "e", "5", "f", "6", "g", "7", "h", "8", "i", "9"))
                .toString().contains("i=text"), "a set of more than 8");
    }
}
