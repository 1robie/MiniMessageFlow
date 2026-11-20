package fr.robie.minimessageflow;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.Style;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TemplateStyleTest {

    private static final Style NOT_ITALIC = Style.style(TextDecoration.ITALIC.withState(false));
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    @Test
    @DisplayName("The root gets the style; the text keeps its own styles over it")
    void rootStyle() {
        MiniMessageFlow flow = MiniMessageFlow.create();
        Template template = flow.compile("<red>Sword %level% <italic>rare").style(NOT_ITALIC);
        Component rendered = template.render(Placeholder.of("level", "3"));
        assertEquals(TextDecoration.State.FALSE, rendered.decoration(TextDecoration.ITALIC));
        assertEquals("Sword 3 rare", PLAIN.serialize(rendered));
        Component italicPart = rendered.children().stream().filter(c -> PLAIN.serialize(c).equals("rare")).findFirst()
                .orElseGet(() -> rendered.children().getLast());
        assertNotEquals(TextDecoration.State.FALSE, italicPart.decoration(TextDecoration.ITALIC));
    }

    @Test
    @DisplayName("A static template is styled once; an empty style gives the template back")
    void staticAndEmpty() {
        MiniMessageFlow flow = MiniMessageFlow.create();
        Template fixed = flow.compile("<gold>Shop").style(NOT_ITALIC);
        assertTrue(fixed.isStatic());
        assertSame(fixed.render(), fixed.render());
        Template plain = flow.compile("x");
        assertSame(plain, plain.style(Style.empty()));
    }

    @Test
    @DisplayName("With remembered renders, unchanged values give the same styled instance, per values")
    void identity() {
        MiniMessageFlow flow = MiniMessageFlow.create();
        Template template = flow.compile("<gray>Coins: %coins%").style(NOT_ITALIC);
        Values steve = flow.newValues().rememberRenders(true).set("coins", 10);
        Values alex = flow.newValues().rememberRenders(true).set("coins", 20);
        Component steveFirst = template.render(steve, null);
        Component alexFirst = template.render(alex, null);
        assertSame(steveFirst, template.render(steve, null));
        assertSame(alexFirst, template.render(alex, null));
        steve.set("coins", 11);
        Component changed = template.render(steve, null);
        assertNotSame(steveFirst, changed);
        assertEquals("Coins: 11", PLAIN.serialize(changed));
        assertEquals(TextDecoration.State.FALSE, changed.decoration(TextDecoration.ITALIC));
    }

    @Test
    @DisplayName("TemplateList.style: every line, and a value with breaks gives styled lines")
    void list() {
        MiniMessageFlow flow = MiniMessageFlow.create();
        TemplateList lore = flow.compileList(List.of("<gray>%desc%", "&oquote"))
                .style(line -> line.source().contains("&o") ? Style.style(TextDecoration.ITALIC.withState(true)) : NOT_ITALIC);
        List<Component> lines = lore.render(Placeholder.of("desc", "a\nb"), null);
        assertEquals(List.of("a", "b", "quote"), lines.stream().map(PLAIN::serialize).toList());
        assertEquals(TextDecoration.State.FALSE, lines.get(0).decoration(TextDecoration.ITALIC));
        assertEquals(TextDecoration.State.FALSE, lines.get(1).decoration(TextDecoration.ITALIC));
        assertEquals(TextDecoration.State.TRUE, lines.get(2).decoration(TextDecoration.ITALIC));
        assertEquals(NamedTextColor.GRAY, colour(lines.get(1)));
    }

    private static net.kyori.adventure.text.format.TextColor colour(Component component) {
        if (component.color() != null) return component.color();
        for (Component child : component.children()) {
            if (child.color() != null) return child.color();
        }
        return null;
    }
}
