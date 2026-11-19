package fr.robie.minimessageflow;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ValuesLayersTest {

    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    @Test
    @DisplayName("A child reads its own value first, then its parent's; setting one never touches the parent")
    void layers() {
        MiniMessageFlow.Builder builder = MiniMessageFlow.builder();
        ValueKey page = builder.key("page");
        ValueKey index = builder.key("index");
        MiniMessageFlow flow = builder.build();
        Template template = flow.compile("%page%/%index%");
        Values menu = flow.newValues().set(page, 2L);
        Values slot = menu.child().set(index, 7L);
        assertEquals("2/7", PLAIN.serialize(template.render(slot, null)));
        assertEquals("2/%index%", PLAIN.serialize(template.render(menu, null)));

        slot.set(page, "shadowed");
        assertEquals("shadowed/7", PLAIN.serialize(template.render(slot, null)));
        assertEquals("2/%index%", PLAIN.serialize(template.render(menu, null)));

        slot.unset(page);
        assertEquals("2/7", PLAIN.serialize(template.render(slot, null)));

        slot.parent().set(page, 3L);
        assertEquals("3/7", PLAIN.serialize(template.render(slot, null)));
        assertSame(menu, slot.root());
        assertSame(menu, menu.root());
        assertNull(menu.parent());

        slot.clear();
        assertEquals("3/%index%", PLAIN.serialize(template.render(slot, null)));
    }

    @Test
    @DisplayName("Modifiers read a parent's value")
    void modifiersThroughLayers() {
        MiniMessageFlow.Builder builder = MiniMessageFlow.builder();
        ValueKey coins = builder.key("coins");
        MiniMessageFlow flow = builder.build();
        Values slot = flow.newValues().set(coins, 1250L).child();
        assertEquals("1,250", PLAIN.serialize(flow.compile("%coins.comma%").render(slot, null)));
    }

    @Test
    @DisplayName("Formatted text is read as tags and codes, its colour ending with it; plain text is not")
    void formatted() {
        MiniMessageFlow.Builder builder = MiniMessageFlow.builder();
        ValueKey rank = builder.key("rank");
        MiniMessageFlow flow = builder.build();
        Template template = flow.compile("%rank% Steve");
        Values values = flow.newValues();

        Component formatted = template.render(values.setFormatted(rank, "&c[Admin]"), null);
        assertEquals("[Admin] Steve", PLAIN.serialize(formatted));
        assertEquals(NamedTextColor.RED, colour(formatted, "[Admin]"));
        assertNull(colour(formatted, " Steve"));

        Component plain = template.render(values.set(rank, "&c[Admin]"), null);
        assertEquals("&c[Admin] Steve", PLAIN.serialize(plain));

        Component raw = flow.compile("%rank.raw%").render(values.setFormatted(rank, "&c[Admin]"), null);
        assertEquals("&c[Admin]", PLAIN.serialize(raw));
    }

    @Test
    @DisplayName("Formatted text with a modifier: the modified text is formatted")
    void formattedWithModifier() {
        MiniMessageFlow.Builder builder = MiniMessageFlow.builder();
        ValueKey rank = builder.key("rank");
        MiniMessageFlow flow = builder.build();
        Component rendered = flow.compile("%rank.upper%").render(flow.newValues().setFormatted(rank, "<red>admin"), null);
        assertEquals("ADMIN", PLAIN.serialize(rendered));
        assertEquals(NamedTextColor.RED, colour(rendered, "ADMIN"));
    }

    @Test
    @DisplayName("The same formatted text gives the same component: parsed once")
    void formattedCached() {
        MiniMessageFlow.Builder builder = MiniMessageFlow.builder();
        ValueKey rank = builder.key("rank");
        MiniMessageFlow flow = builder.build();
        Template template = flow.compile("<gray>Rank: %rank%");
        Values values = flow.newValues().setFormatted(rank, "&c[Admin]");
        assertSame(template.render(values, null), template.render(values, null));
    }

    @Test
    @DisplayName("Placeholder.registerFormatted reads tags; register does not")
    void placeholderFormatted() {
        MiniMessageFlow flow = MiniMessageFlow.create();
        Template template = flow.compile("%v%");
        assertEquals("x", PLAIN.serialize(template.render(Placeholder.builder().registerFormatted("v", "<red>x").build())));
        assertEquals("<red>x", PLAIN.serialize(template.render(Placeholder.builder().register("v", "<red>x").build())));
        assertEquals("<red>x", PLAIN.serialize(flow.compile("%v.raw%")
                .render(Placeholder.builder().registerFormatted("v", "<red>x").build())));
        assertEquals("Placeholder{v=formatted}", Placeholder.builder().registerFormatted("v", "x").build().toString());
    }

    @Test
    @DisplayName("A key declared after build: set, read by messages compiled before and after")
    void lateKeys() {
        MiniMessageFlow.Builder builder = MiniMessageFlow.builder();
        ValueKey early = builder.key("early");
        MiniMessageFlow flow = builder.build();
        Template before = flow.compile("%early% %late% %late.upper%");
        Values values = flow.newValues().set(early, "a");
        ValueKey late = flow.key("late");
        assertSame(late, flow.key("late"));
        assertSame(early, flow.key("early"));
        values.set(late, "b");
        Template after = flow.compile("%early% %late% %late.upper%");
        assertEquals("a b B", PLAIN.serialize(before.render(values, null)));
        assertEquals("a b B", PLAIN.serialize(after.render(values, null)));
        assertEquals("a b B", PLAIN.serialize(after.render(flow.newValues().set(early, "a").set(late, "b"), null)));
        assertThrows(IllegalArgumentException.class, () -> flow.key("bad name"));
        assertThrows(IllegalStateException.class, () -> ValueKeys.none().add("x"));
    }

    @Test
    @DisplayName("Two instances built on the same keys share their ValueKeys and Values")
    void sharedKeys() {
        MiniMessageFlow first = MiniMessageFlow.builder().build();
        ValueKey name = first.key("name");
        Values values = first.newValues().set(name, "Steve");
        MiniMessageFlow second = MiniMessageFlow.builder().keys(first.keys()).tag("x", net.kyori.adventure.text.minimessage.tag.Tag.preProcessParsed("<red>")).build();
        assertSame(first.keys(), second.keys());
        assertSame(name, second.key("name"));
        assertEquals("Steve", PLAIN.serialize(second.compile("<x>%name%").render(values, null)));
        ValueKey late = MiniMessageFlow.builder().keys(first.keys()).key("late");
        assertSame(late, first.key("late"));
    }

    @Test
    @DisplayName("toPlaceholder copies every layer, this one's first, and keeps the kinds")
    void snapshot() {
        MiniMessageFlow.Builder builder = MiniMessageFlow.builder();
        ValueKey a = builder.key("a");
        ValueKey b = builder.key("b");
        MiniMessageFlow flow = builder.build();
        Values parent = flow.newValues().set(a, "parent-a").setFormatted(b, "<red>b");
        Values child = parent.child().set(a, "child-a");
        Placeholder copy = child.toPlaceholder();
        assertEquals("Placeholder{a=text, b=formatted}", copy.toString().replace("b=formatted, a=text", "a=text, b=formatted"));
        child.set(a, "changed");
        assertEquals("child-a b", PLAIN.serialize(flow.compile("%a% %b%").render(copy)));
        assertEquals("Values{a=text}", child.toString());
        assertEquals("Values{a=text, b=formatted}", parent.toString());
    }

    private static TextColor colour(Component node, String text) {
        return colour(node, text, null);
    }

    private static TextColor colour(Component node, String text, TextColor parent) {
        TextColor current = node.color() != null ? node.color() : parent;
        if (node instanceof TextComponent t && t.content().equals(text)) {
            return current;
        }
        for (Component child : node.children()) {
            TextColor found = colour(child, text, current);
            if (found != null) {
                return found;
            }
        }
        return null;
    }
}
