package fr.robie.minimessageflow;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ValuesByNameTest {

    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    private static String plain(Component component) {
        return PLAIN.serialize(component);
    }

    @Test
    @DisplayName("set by name declares the key; templates compiled before and after read it")
    void byName() {
        MiniMessageFlow flow = MiniMessageFlow.create();
        Template before = flow.compile("Page %page%/%max-page%");
        Values values = flow.newValues().set("page", 3L).set("max-page", "7");
        Template after = flow.compile("Page %page%/%max-page%");
        assertEquals("Page 3/7", plain(before.render(values)));
        assertEquals("Page 3/7", plain(after.render(values)));
        assertTrue(values.isSet("page"));
        values.unset("page");
        assertFalse(values.isSet("page"));
        assertFalse(values.isSet("never"));
        values.unset("never");
        assertNull(flow.keys().get("never"), "reading a name does not declare it");
    }

    @Test
    @DisplayName("A dotted name set directly wins over a modifier of its first part")
    void dottedName() {
        MiniMessageFlow flow = MiniMessageFlow.create();
        Values values = flow.newValues().set("key", "50").set("key.percent", "50%");
        assertEquals("50% 50", plain(flow.compile("%key.percent% %key.int%").render(values)));
    }

    @Test
    @DisplayName("A name no placeholder can have throws")
    void invalidName() {
        Values values = MiniMessageFlow.create().newValues();
        assertThrows(IllegalArgumentException.class, () -> values.set("has space", "x"));
        assertThrows(IllegalArgumentException.class, () -> values.set("a%b", "x"));
        assertThrows(IllegalArgumentException.class, () -> values.set("", "x"));
    }

    @Test
    @DisplayName("Formatted by name is read as tags; plain by name is not")
    void formattedByName() {
        MiniMessageFlow flow = MiniMessageFlow.create();
        Values values = flow.newValues().setFormatted("rank", "<red>Admin").set("nick", "<red>Steve");
        Component rendered = flow.compile("%rank% %nick%").render(values);
        assertEquals("Admin <red>Steve", plain(rendered));
    }

    @Test
    @DisplayName("get reads this layer, then the parents, then the lookups; no modifiers")
    void get() {
        MiniMessageFlow flow = MiniMessageFlow.create();
        Values root = flow.newValues().set("player", "Steve").set("icon", Component.text("*", NamedTextColor.GOLD));
        root.lookup("canvas_", rest -> rest.equals("tab") ? "shop" : null);
        Values child = root.child().set("entry", "Sword").setComputed(flow.key("time"), () -> "noon");
        assertEquals("Steve", child.get("player"));
        assertEquals("Sword", child.get("entry"));
        assertEquals("*", child.get("icon"));
        assertEquals("noon", child.get("time"));
        assertEquals("shop", child.get("canvas_tab"));
        assertNull(child.get("canvas_other"));
        assertNull(child.get("player.upper"));
        assertNull(root.get("entry"));
        assertNull(child.get("unknown"));
    }

    @Test
    @DisplayName("merge copies the other layer only, replacing; values of another instance throw")
    void merge() {
        MiniMessageFlow flow = MiniMessageFlow.create();
        Values parent = flow.newValues().set("player", "Steve");
        Values other = parent.child().set("a", "1").setFormatted("b", "<red>2");
        other.lookup("x_", rest -> "looked");
        Values target = flow.newValues().set("a", "old").set("c", "3");
        target.merge(other);
        assertEquals("1", target.get("a"));
        assertEquals("3", target.get("c"));
        assertNull(target.get("player"), "the other's parents are not copied");
        assertEquals("looked", target.get("x_y"));
        assertEquals("1 2 3", plain(flow.compile("%a% %b% %c%").render(target)));
        assertThrows(IllegalArgumentException.class, () -> target.merge(MiniMessageFlow.create().newValues()));
    }

    @Test
    @DisplayName("flatten gives every visible value, the nearest winning")
    void flatten() {
        MiniMessageFlow flow = MiniMessageFlow.create();
        Values root = flow.newValues().set("a", "root").set("b", "root");
        Values child = root.child().set("b", "child");
        assertEquals(Map.of("a", "root", "b", "child"), child.flatten());
        assertEquals(Map.of("a", "root", "b", "root"), root.flatten());
    }

    @Test
    @DisplayName("copy is a root holding what the layer sees, independent afterwards")
    void copy() {
        MiniMessageFlow flow = MiniMessageFlow.create();
        Values root = flow.newValues().set("a", "root").setFormatted("f", "<red>x");
        root.lookup("s_", rest -> "root-lookup");
        Values child = root.child().set("b", "child");
        child.lookup("s_", rest -> "child-lookup");
        Values copy = child.copy();
        assertNull(copy.parent());
        assertEquals("root", copy.get("a"));
        assertEquals("child", copy.get("b"));
        assertEquals("child-lookup", copy.get("s_k"), "the nearest lookup of a prefix wins");
        assertEquals("Values{a=text, f=formatted, b=text}", copy.toString());
        root.set("a", "changed");
        child.set("b", "changed");
        assertEquals("root", copy.get("a"));
        assertEquals("child", copy.get("b"));
        copy.set("a", "mine");
        assertEquals("changed", root.get("a"));
    }
}
