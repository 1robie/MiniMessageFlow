package fr.robie.minimessageflow;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class TextTemplateTest {

    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    @Test
    @DisplayName("Placeholders are replaced, tags and codes stay as written, <key> is text")
    void command() {
        MiniMessageFlow flow = MiniMessageFlow.create();
        TextTemplate command = flow.compileText("/msg <player> &c%name% has %coins.comma% coins");
        assertEquals("/msg <player> &cSteve has 1,250 coins",
                command.render(Placeholder.of("name", "Steve", "coins", "1250")));
        assertEquals(List.of("name", "coins.comma"), command.placeholders());
        assertFalse(command.isStatic());
    }

    @Test
    @DisplayName("Formatted text, a resolver's text and a component are placed as written / as plain text")
    void kinds() {
        MiniMessageFlow.Builder builder = MiniMessageFlow.builder().resolver(Resolver.of(Placeholder.of("server", "&aLobby")));
        ValueKey rank = builder.key("rank");
        ValueKey item = builder.key("item");
        MiniMessageFlow flow = builder.build();
        Values values = flow.newValues().setFormatted(rank, "<red>Admin").set(item, Component.text("Sword", NamedTextColor.GOLD));
        assertEquals("<red>Admin Sword &aLobby", flow.compileText("%rank% %item% %server%").render(values, null));
    }

    @Test
    @DisplayName("Without a value a placeholder stays as written; a lone % stays")
    void missing() {
        TextTemplate text = MiniMessageFlow.create().compileText("100% of %nobody% and %x.upper%");
        assertEquals("100% of %nobody% and %x.upper%", text.render());
    }

    @Test
    @DisplayName("No placeholder: static, the source itself")
    void fixed() {
        TextTemplate text = MiniMessageFlow.create().compileText("/spawn");
        assertTrue(text.isStatic());
        assertSame(text.source(), text.render());
    }

    @Test
    @DisplayName("A key written twice is asked once per render")
    void askedOnce() {
        AtomicInteger asked = new AtomicInteger();
        TextTemplate text = MiniMessageFlow.create().compileText("%n%-%n%-%n.upper%");
        assertEquals("1-1-2", text.render(Placeholder.of("n", () -> String.valueOf(asked.incrementAndGet()))));
        assertEquals(2, asked.get());
    }

    @Test
    @DisplayName("Prefix lookups: asked for keys without a value, at each render, through layers, with modifiers")
    void lookups() {
        MiniMessageFlow.Builder builder = MiniMessageFlow.builder();
        ValueKey declared = builder.key("state_fixed");
        MiniMessageFlow flow = builder.build();
        AtomicInteger counter = new AtomicInteger(1000);
        Values menu = flow.newValues().lookup("state_", rest -> rest.equals("count") ? String.valueOf(counter.get()) : null);
        Values slot = menu.child().set(declared, "set");
        Template template = flow.compile("%state_count% %state_count.comma% %state_fixed% %state_other%");
        assertEquals("1000 1,000 set %state_other%", PLAIN.serialize(template.render(slot, null)));
        counter.set(2000);
        assertEquals("2000 2,000 set %state_other%", PLAIN.serialize(template.render(slot, null)));
        assertEquals("2000", flow.compileText("%state_count%").render(slot, null));

        slot.lookupFormatted("state_", rest -> "<red>" + rest);
        Component formatted = flow.compile("%state_x%").render(slot, null);
        assertEquals("x", PLAIN.serialize(formatted));
        assertEquals("<red>x", PLAIN.serialize(flow.compile("%state_x.raw%").render(slot, null)));

        slot.removeLookup("state_");
        assertEquals("2000", PLAIN.serialize(flow.compile("%state_count%").render(slot, null)));
        assertThrows(IllegalArgumentException.class, () -> menu.lookup("", rest -> rest));
    }

    @Test
    @DisplayName("A lookup that throws leaves the placeholder as written and is reported")
    void lookupThrows() {
        AtomicInteger errors = new AtomicInteger();
        MiniMessageFlow flow = MiniMessageFlow.builder().errorHandler((key, error) -> errors.incrementAndGet()).build();
        Values values = flow.newValues().lookup("bad_", rest -> {
            throw new IllegalStateException("down");
        });
        assertEquals("%bad_x%", PLAIN.serialize(flow.compile("%bad_x%").render(values, null)));
        assertEquals(1, errors.get());
    }
}
