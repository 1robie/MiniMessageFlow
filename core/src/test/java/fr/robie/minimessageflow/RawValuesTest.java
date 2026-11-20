package fr.robie.minimessageflow;

import net.kyori.adventure.pointer.Pointered;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RawValuesTest {

    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();
    private static final Pointered VIEWER = new Pointered() {
    };

    private static MiniMessageFlow flow() {
        return MiniMessageFlow.builder()
                .resolver((key, viewer) -> key.equals("rank") ? "<red>[Admin]" : null)
                .placeholdersInFormattedValues(true)
                .build();
    }

    @Test
    @DisplayName("With raw values, formatted values, resolver text and components are placed as written")
    void raw() {
        MiniMessageFlow flow = flow();
        Template template = flow.compile("<green>Hi %name% %rank% %badge%");
        Values values = flow.newValues().setFormatted("name", "<bold>Steve").set("badge", Component.text("[VIP]", NamedTextColor.GOLD));

        Component normal = template.render(values, VIEWER);
        assertEquals("Hi Steve [Admin] [VIP]", PLAIN.serialize(normal));
        assertEquals(NamedTextColor.RED, colour(normal, "[Admin]"));

        Values raw = values.child().rawValues(true);
        Component escaped = template.render(raw, VIEWER);
        assertEquals("Hi <bold>Steve <red>[Admin] [VIP]", PLAIN.serialize(escaped));
        assertEquals(NamedTextColor.GREEN, colour(escaped, "<red>[Admin]"));
    }

    @Test
    @DisplayName("A child keeps the setting, and turning it off restores formatting; modifiers still apply")
    void childAndModifiers() {
        MiniMessageFlow flow = flow();
        Values raw = flow.newValues().rawValues(true).setFormatted("name", "<bold>steve");
        Values child = raw.child();
        assertTrue(child.rawValues());
        assertEquals("<BOLD>STEVE", PLAIN.serialize(flow.compile("%name.upper%").render(child, VIEWER)));

        raw.rawValues(false);
        assertEquals("steve", PLAIN.serialize(flow.compile("%name%").render(raw, VIEWER)));
    }

    @Test
    @DisplayName("The same template renders raw and formatted values alternately without mixing them up")
    void alternate() {
        MiniMessageFlow flow = flow();
        Template template = flow.compile("%name%");
        Values values = flow.newValues().setFormatted("name", "<red>x").rememberRenders(true);
        Values raw = values.child().rawValues(true);
        for (int i = 0; i < 3; i++) {
            assertEquals("x", PLAIN.serialize(template.render(values, VIEWER)));
            assertEquals("<red>x", PLAIN.serialize(template.render(raw, VIEWER)));
        }
    }

    private static TextColor colour(Component component, String text) {
        if (component instanceof TextComponent textComponent && textComponent.content().equals(text)) {
            return component.color();
        }
        for (Component child : component.children()) {
            TextColor found = colour(child, text);
            if (found != null) {
                return found.equals(NamedTextColor.WHITE) && child.color() == null ? component.color() : found;
            }
        }
        if (component instanceof TextComponent textComponent && textComponent.content().contains(text)) {
            return component.color();
        }
        return null;
    }
}
