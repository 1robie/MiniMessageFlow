package fr.robie.minimessageflow;

import net.kyori.adventure.pointer.Pointered;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class PlaceholdersInValuesTest {

    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();
    private static final Pointered VIEWER = new Pointered() {
    };

    /**
     * A PlaceholderAPI stand-in: {@code balance} depends on the viewer, {@code rank} gives formatted text.
     */
    private static final class Papi implements Resolver {
        final AtomicInteger asked = new AtomicInteger();

        @Override
        public @Nullable String resolve(@NotNull String key, @Nullable Pointered viewer) {
            this.asked.incrementAndGet();
            return switch (key) {
                case "balance" -> viewer == null ? "0" : "1250";
                case "rank" -> "&c[Admin]";
                default -> null;
            };
        }
    }

    private static MiniMessageFlow flow(Papi papi, boolean on) {
        return MiniMessageFlow.builder().resolver(papi).placeholdersInFormattedValues(on).build();
    }

    @Test
    @DisplayName("A formatted value's placeholders are read through the resolvers, for the viewer, then parsed")
    void formatted() {
        Papi papi = new Papi();
        MiniMessageFlow flow = flow(papi, true);
        Values values = flow.newValues().setFormatted("price", "<gold>%balance%$").setFormatted("who", "%rank% Steve");
        Pointered viewer = VIEWER;
        Component rendered = flow.compile("Price: %price% - %who%").render(values, viewer);
        assertEquals("Price: 1250$ - [Admin] Steve", PLAIN.serialize(rendered));
        assertEquals(NamedTextColor.GOLD, colour(rendered, "1250$"));
        assertEquals(NamedTextColor.RED, colour(rendered, "[Admin] Steve"));
        assertEquals("Price: 0$ - [Admin] Steve", PLAIN.serialize(flow.compile("Price: %price% - %who%").render(values, null)));
    }

    @Test
    @DisplayName("Plain values, raw and the option off are never read; an unknown placeholder stays as written")
    void notRead() {
        Papi papi = new Papi();
        MiniMessageFlow flow = flow(papi, true);
        Values values = flow.newValues().set("typed", "%balance%").setFormatted("price", "%balance% %nobody%");
        assertEquals("%balance% 1250 %nobody% %balance% %nobody%",
                PLAIN.serialize(flow.compile("%typed% %price% %price.raw%").render(values, VIEWER)));

        MiniMessageFlow off = flow(new Papi(), false);
        Values offValues = off.newValues().setFormatted("price", "%balance%$");
        assertEquals("%balance%$", PLAIN.serialize(off.compile("%price%").render(offValues, VIEWER)));
    }

    @Test
    @DisplayName("Placeholder.registerFormatted and formatted lookups are read too; one level only")
    void placeholderAndLookups() {
        Papi papi = new Papi();
        MiniMessageFlow flow = MiniMessageFlow.builder().resolver(papi)
                .resolver((key, viewer) -> key.equals("loop") ? "%balance%" : null)
                .placeholdersInFormattedValues(true).build();
        Pointered viewer = VIEWER;
        assertEquals("1250", PLAIN.serialize(flow.compile("%v%").render(Placeholder.builder().registerFormatted("v", "%balance%").build(), viewer)));
        Values values = flow.newValues().lookupFormatted("state_", rest -> "%balance%");
        assertEquals("1250", PLAIN.serialize(flow.compile("%state_x%").render(values, viewer)));
        Values loop = flow.newValues().setFormatted("v", "%loop%");
        assertEquals("%balance%", PLAIN.serialize(flow.compile("%v%").render(loop, viewer)));
    }

    @Test
    @DisplayName("A value without % asks no resolver")
    void noPercent() {
        Papi papi = new Papi();
        MiniMessageFlow flow = flow(papi, true);
        Values values = flow.newValues().setFormatted("name", "&aSteve");
        flow.compile("%name%").render(values, VIEWER);
        assertEquals(0, papi.asked.get());
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
            if (found != null) return found;
        }
        return null;
    }
}
