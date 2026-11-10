package fr.robie.minimessageflow.testkit;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ComponentRunsTest {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    @Test
    @DisplayName("A style on the parent and the same style on each leaf give the same runs")
    void nestedEqualsFlattened() {
        Component nested = Component.text().color(NamedTextColor.RED)
                .append(Component.text("Hello "))
                .append(Component.text("Steve", NamedTextColor.RED).decorate(TextDecoration.BOLD))
                .build();
        Component flat = Component.text()
                .append(Component.text("Hel", NamedTextColor.RED))
                .append(Component.text("lo ", NamedTextColor.RED))
                .append(Component.text("Steve", NamedTextColor.RED, TextDecoration.BOLD))
                .build();
        assertNotEquals(nested, flat);
        assertEquals(ComponentRuns.of(nested), ComponentRuns.of(flat));
        assertEquals(2, ComponentRuns.of(flat).size());
    }

    @Test
    @DisplayName("Matches what MiniMessage builds for the same text")
    void matchesMiniMessage() {
        Component built = Component.text()
                .append(Component.text("a", NamedTextColor.RED))
                .append(Component.text("b", NamedTextColor.RED, TextDecoration.BOLD))
                .build();
        assertEquals(ComponentRuns.of(built), ComponentRuns.of(MM.deserialize("<red>a<bold>b")));
    }

    @Test
    @DisplayName("A decoration set to false is the same as not set; true is not")
    void decorations() {
        assertEquals(ComponentRuns.of(Component.text("x")),
                ComponentRuns.of(Component.text("x").decoration(TextDecoration.ITALIC, false)));
        assertNotEquals(ComponentRuns.of(Component.text("x")),
                ComponentRuns.of(Component.text("x").decoration(TextDecoration.ITALIC, true)));
    }

    @Test
    @DisplayName("Colours, clicks and hover texts count")
    void differences() {
        assertNotEquals(ComponentRuns.of(MM.deserialize("<red>x")), ComponentRuns.of(MM.deserialize("<blue>x")));
        assertNotEquals(ComponentRuns.of(Component.text("x").clickEvent(ClickEvent.runCommand("/a"))),
                ComponentRuns.of(Component.text("x").clickEvent(ClickEvent.runCommand("/b"))));
        assertNotEquals(ComponentRuns.of(MM.deserialize("<hover:show_text:'<red>a'>x")),
                ComponentRuns.of(MM.deserialize("<hover:show_text:'<blue>a'>x")));
    }

    @Test
    @DisplayName("Hover texts are compared by their runs, not their trees")
    void hoverByRuns() {
        Component nested = Component.text("x").hoverEvent(HoverEvent.showText(
                Component.text().color(NamedTextColor.RED).append(Component.text("a")).append(Component.text("b")).build()));
        Component flat = Component.text("x").hoverEvent(HoverEvent.showText(Component.text("ab", NamedTextColor.RED)));
        assertEquals(ComponentRuns.of(nested), ComponentRuns.of(flat));
    }

    @Test
    @DisplayName("Empty text is dropped, a non-text component is one run")
    void emptyAndNonText() {
        assertEquals(ComponentRuns.of(Component.text("a")),
                ComponentRuns.of(Component.text().append(Component.empty()).append(Component.text("a")).build()));
        var runs = ComponentRuns.of(Component.text("a").append(Component.translatable("item.minecraft.stone")).append(Component.text("b")));
        assertEquals(3, runs.size(), runs::toString);
        assertFalse(runs.get(1).text());
    }
}
