package fr.robie.minimessageflow;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.Style;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RenderLinesTest {

    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();
    private static final MiniMessageFlow FLOW = MiniMessageFlow.create();

    private static List<String> plain(List<Component> lines) {
        return lines.stream().map(PLAIN::serialize).toList();
    }

    @Test
    @DisplayName("Without a break: one line, the rendered component itself")
    void oneLine() {
        Template template = FLOW.compile("<red>Hello %name%");
        Placeholder values = Placeholder.of("name", "Steve");
        List<Component> lines = template.renderLines(values, null);
        assertEquals(1, lines.size());
        assertSame(template.render(values, null), lines.get(0));
    }

    @Test
    @DisplayName("A value with breaks gives several lines, the open colour carried to each")
    void valueBreaks() {
        List<Component> lines = FLOW.compile("<gray>Desc: <yellow>%desc%").renderLines(Placeholder.of("desc", "one\ntwo\nthree"), null);
        assertEquals(List.of("Desc: one", "two", "three"), plain(lines));
        assertEquals(NamedTextColor.YELLOW, colourOf(lines.get(1), "two"));
        assertEquals(NamedTextColor.YELLOW, colourOf(lines.get(2), "three"));
        assertEquals(NamedTextColor.GRAY, colourOf(lines.get(0), "Desc: "));
    }

    @Test
    @DisplayName("<newline> and a break in the text split too; empty and trailing lines are kept")
    void newlineTag() {
        assertEquals(List.of("a", "b", "", "c", ""), plain(FLOW.compile("a<newline>b\n\nc\n").renderLines(Placeholder.empty(), null)));
    }

    @Test
    @DisplayName("Decorations, click and insertion carry over; a hover stays whole")
    void events() {
        Component hover = Component.text("tip\nmore");
        Component built = Component.text().content("x\ny")
                .style(Style.style(TextDecoration.BOLD).clickEvent(ClickEvent.runCommand("/go")).insertion("ins")
                        .hoverEvent(HoverEvent.showText(hover)))
                .build();
        List<Component> lines = fr.robie.minimessageflow.internal.ComponentLines.split(built);
        assertEquals(2, lines.size());
        for (Component line : lines) {
            assertEquals(TextDecoration.State.TRUE, line.decoration(TextDecoration.BOLD));
            assertEquals(ClickEvent.runCommand("/go"), line.clickEvent());
            assertEquals("ins", line.insertion());
            assertNotNull(line.hoverEvent());
            assertSame(hover, line.hoverEvent().value());
        }
    }

    @Test
    @DisplayName("A gradient over several lines keeps the colours computed over the whole text")
    void gradient() {
        Component whole = FLOW.compile("<gradient:red:blue>%v%</gradient>").render(Placeholder.of("v", "ab\ncd"));
        List<Component> lines = FLOW.compile("<gradient:red:blue>%v%</gradient>").renderLines(Placeholder.of("v", "ab\ncd"), null);
        assertEquals(List.of("ab", "cd"), plain(lines));
        assertEquals(colourOf(whole, "d"), colourOf(lines.get(1), "d"));
        assertEquals(colourOf(whole, "a"), colourOf(lines.get(0), "a"));
    }

    @Test
    @DisplayName("With Values")
    void values() {
        MiniMessageFlow.Builder builder = MiniMessageFlow.builder();
        ValueKey lore = builder.key("lore");
        MiniMessageFlow flow = builder.build();
        assertEquals(List.of("1", "2"), plain(flow.compile("%lore%").renderLines(flow.newValues().setFormatted(lore, "1\n<red>2"), null)));
    }

    private static TextColor colourOf(Component node, String text) {
        return colourOf(node, text, null);
    }

    private static TextColor colourOf(Component node, String text, TextColor parent) {
        TextColor current = node.color() != null ? node.color() : parent;
        if (node instanceof TextComponent t && t.content().equals(text)) {
            return current;
        }
        for (Component child : node.children()) {
            TextColor found = colourOf(child, text, current);
            if (found != null) {
                return found;
            }
        }
        return null;
    }
}
