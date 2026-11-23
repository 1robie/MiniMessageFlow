package fr.robie.minimessageflow;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.Style;
import net.kyori.adventure.text.format.TextDecoration;
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
    @DisplayName("A styled template follows a tag added after it was styled")
    void styledFollowsTag() {
        MiniMessageFlow flow = MiniMessageFlow.create();
        Style notItalic = Style.style(TextDecoration.ITALIC.withState(false));
        Template template = flow.compile("<uwu>hi</uwu>").style(notItalic);
        assertEquals("<uwu>hi</uwu>", plain(template.render()));
        flow.tag("uwu", Tag.styling(NamedTextColor.LIGHT_PURPLE));
        Component rendered = template.render();
        assertEquals("hi", plain(rendered));
        assertEquals(TextDecoration.State.FALSE, rendered.decoration(TextDecoration.ITALIC));
        assertTrue(flow.removeTag("uwu"));
        assertEquals("<uwu>hi</uwu>", plain(template.render()));
    }

    @Test
    @DisplayName("A static styled template follows a preprocessor set after it was styled")
    void styledStaticFollowsPreprocessor() {
        MiniMessageFlow flow = MiniMessageFlow.create();
        Style notItalic = Style.style(TextDecoration.ITALIC.withState(false));
        Template template = flow.compile("I :heart: you").style(notItalic);
        assertTrue(template.isStatic());
        assertEquals("I :heart: you", plain(template.render()));
        flow.preprocessor(message -> message.replace(":heart:", "<red>❤</red>"));
        Component rendered = template.render();
        assertEquals("I ❤ you", plain(rendered));
        assertEquals(TextDecoration.State.FALSE, rendered.decoration(TextDecoration.ITALIC));
        assertTrue(template.isStatic());
    }

    @Test
    @DisplayName("A static styled template becomes dynamic when a resolver is added, keeping its style")
    void styledBecomesDynamic() {
        MiniMessageFlow flow = MiniMessageFlow.create();
        Style notItalic = Style.style(TextDecoration.ITALIC.withState(false));
        Template template = flow.compile("Rank: %rank%").style(notItalic);
        TemplateList lines = flow.compileList(List.of("%rank%", "plain")).style(notItalic);
        assertEquals("Rank: %rank%", plain(template.render()));
        String[] rank = {"Admin"};
        flow.resolver((key, viewer) -> key.equals("rank") ? rank[0] : null);
        Component rendered = template.render();
        assertEquals("Rank: Admin", plain(rendered));
        assertEquals(TextDecoration.State.FALSE, rendered.decoration(TextDecoration.ITALIC));
        rank[0] = "Owner";
        assertEquals("Rank: Owner", plain(template.render()));
        List<Component> rendered2 = lines.render();
        assertEquals("Owner", plain(rendered2.getFirst()));
        for (Component line : rendered2) {
            assertEquals(TextDecoration.State.FALSE, line.decoration(TextDecoration.ITALIC));
        }
    }

    @Test
    @DisplayName("A styled line split in several lines gives the style to each")
    void styledLines() {
        MiniMessageFlow flow = MiniMessageFlow.create();
        Style notItalic = Style.style(TextDecoration.ITALIC.withState(false));
        Template template = flow.compile("one<newline>%two%").style(notItalic);
        List<Component> lines = template.renderLines(Placeholder.of("two", "two"), null);
        assertEquals(2, lines.size());
        assertEquals("two", plain(lines.get(1)));
        for (Component line : lines) {
            assertEquals(TextDecoration.State.FALSE, line.decoration(TextDecoration.ITALIC));
        }
    }

    @Test
    @DisplayName("Renders on other threads while the definitions change end on the last definitions")
    void changesWhileRendering() throws Exception {
        MiniMessageFlow flow = MiniMessageFlow.create();
        Template template = flow.compile("%server%");
        TextTemplate text = flow.compileText("%server%");
        java.util.concurrent.atomic.AtomicBoolean stop = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.atomic.AtomicReference<Throwable> failure = new java.util.concurrent.atomic.AtomicReference<>();
        List<Thread> threads = new java.util.ArrayList<>();
        for (int t = 0; t < 4; t++) {
            Thread thread = new Thread(() -> {
                try {
                    while (!stop.get()) {
                        String rendered = plain(template.render());
                        String written = text.render();
                        if (!rendered.startsWith("Lobby") && !rendered.equals("%server%")) {
                            throw new AssertionError(rendered);
                        }
                        if (!written.startsWith("Lobby") && !written.equals("%server%")) {
                            throw new AssertionError(written);
                        }
                    }
                } catch (Throwable e) {
                    failure.compareAndSet(null, e);
                }
            });
            thread.start();
            threads.add(thread);
        }
        for (int i = 0; i < 500; i++) {
            flow.constant("server", "Lobby" + i);
            assertTrue(flow.removeConstant("server"));
        }
        flow.constant("server", "Lobby-last");
        stop.set(true);
        for (Thread thread : threads) {
            thread.join();
        }
        assertNull(failure.get());
        assertEquals("Lobby-last", plain(template.render()));
        assertEquals("Lobby-last", text.render());
        assertEquals("Lobby-last", plain(flow.format("%server%")));
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
    @DisplayName("numberStyle changed after build: templates compiled before and Values made before follow it")
    void numberStyleAfterBuild() {
        MiniMessageFlow flow = MiniMessageFlow.builder().numberStyle(NumberStyle.ENGLISH).build();
        Template template = flow.compile("%price.comma% %ratio%");
        TextTemplate text = flow.compileText("%price.comma%");
        Values values = flow.newValues().set("price", "1234567").set("ratio", 2.5);
        assertEquals("1,234,567 2.5", plain(template.render(values)));
        flow.numberStyle(NumberStyle.EUROPEAN);
        assertSame(NumberStyle.EUROPEAN, flow.numberStyle());
        values.set("ratio", 2.5);
        assertEquals("1.234.567 2,5", plain(template.render(values)));
        assertEquals("1.234.567", text.render(values, null));
        assertEquals("1.234.567", flow.formatText("%price.comma%", values, null));
        Values child = values.child().set("half", 0.5);
        assertEquals("0,5", child.get("half"));
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
