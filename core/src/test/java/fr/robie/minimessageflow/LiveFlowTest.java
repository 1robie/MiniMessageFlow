package fr.robie.minimessageflow;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LiveFlowTest {

    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    private static String plain(Component component) {
        return PLAIN.serialize(component);
    }

    @Test
    @DisplayName("A tag added after build is read by a template compiled before")
    void tagAfterBuild() {
        MiniMessageFlow flow = MiniMessageFlow.create();
        Template template = flow.compile("<uwu>hi</uwu>");
        assertEquals("<uwu>hi</uwu>", plain(template.render()));
        flow.tag("uwu", Tag.styling(NamedTextColor.LIGHT_PURPLE));
        Component rendered = template.render();
        assertEquals("hi", plain(rendered));
        assertEquals(NamedTextColor.LIGHT_PURPLE, rendered.children().isEmpty() ? rendered.color() : rendered.children().getFirst().color());
        assertTrue(flow.removeTag("uwu"));
        assertEquals("<uwu>hi</uwu>", plain(template.render()));
    }

    @Test
    @DisplayName("format() and compileText follow changes too")
    void formatAndText() {
        MiniMessageFlow flow = MiniMessageFlow.create();
        TextTemplate command = flow.compileText("/pay %server%");
        assertEquals("%server%", plain(flow.format("%server%")));
        flow.constant("server", "Lobby");
        assertEquals("Lobby", plain(flow.format("%server%")));
        assertEquals("/pay Lobby", command.render());
        assertTrue(flow.removeConstant("server"));
        assertEquals("/pay %server%", command.render());
    }

    @Test
    @DisplayName("A resolver and a value tag added after build")
    void resolverAndValueTag() {
        MiniMessageFlow flow = MiniMessageFlow.create();
        Template template = flow.compile("%rank% <stat:kills>");
        Resolver ranks = (key, viewer) -> key.equals("rank") ? "<red>Admin" : null;
        flow.resolver(ranks).valueTag("stat", (arguments, viewer) -> "12");
        assertEquals("Admin 12", plain(template.render()));
        assertTrue(flow.removeResolver(ranks));
        assertTrue(flow.removeValueTag("stat"));
        assertEquals("%rank% <stat:kills>", plain(template.render()));
    }

    @Test
    @DisplayName("A tag cannot hide a value tag, nor a value tag sit under a tag")
    void conflicts() {
        MiniMessageFlow flow = MiniMessageFlow.create().valueTag("stat", (arguments, viewer) -> "1");
        assertThrows(IllegalArgumentException.class, () -> flow.tag("stat", Tag.preProcessParsed("x")));
        flow.tag("shiny", Tag.preProcessParsed("x"));
        assertThrows(IllegalArgumentException.class, () -> flow.valueTag("shiny", (arguments, viewer) -> "1"));
        assertThrows(IllegalArgumentException.class, () -> flow.valueTag("red", (arguments, viewer) -> "1"));
    }

    @Test
    @DisplayName("compileList renders every line, a value with a break giving two")
    void list() {
        MiniMessageFlow flow = MiniMessageFlow.create();
        TemplateList lore = flow.compileList(List.of("<gray>Price: %price%", "%desc%", "<green>Click to buy"));
        assertEquals(List.of("price", "desc"), lore.placeholders());
        assertFalse(lore.isStatic());
        List<Component> lines = lore.render(Placeholder.of("price", "10", "desc", "Sharp\nShiny"));
        assertEquals(List.of("Price: 10", "Sharp", "Shiny", "Click to buy"), lines.stream().map(LiveFlowTest::plain).toList());
        assertTrue(flow.compileList(List.of()).render().isEmpty());
        assertTrue(flow.compileList(List.of("<red>a", "b")).isStatic());
    }
}
