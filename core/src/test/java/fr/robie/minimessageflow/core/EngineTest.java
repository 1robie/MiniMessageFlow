package fr.robie.minimessageflow.core;

import fr.robie.minimessageflow.MiniMessageFlow;
import fr.robie.minimessageflow.Placeholder;
import fr.robie.minimessageflow.Resolver;
import fr.robie.minimessageflow.Template;
import fr.robie.minimessageflow.testkit.ComponentRuns;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What the contract can't see: compiling once, caches, options.
 */
class EngineTest {

    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();
    private final MiniMessageFlow flow = MiniMessageFlow.create();

    @Test
    @DisplayName("A message without placeholders is static: every render is the same component")
    void staticTemplate() {
        Template template = this.flow.compile("<green>Welcome &lhome");
        assertTrue(template.isStatic());
        assertSame(template.render(), template.render(Placeholder.of("x", "y")));
        assertEquals("Welcome home", PLAIN.serialize(template.render()));
    }

    @Test
    @DisplayName("A placeholder, in either syntax, makes a template dynamic")
    void dynamicTemplates() {
        assertFalse(this.flow.compile("Hi %name%").isStatic());
        assertFalse(this.flow.compile("Hi <name>").isStatic());
        assertFalse(this.flow.compile("<click:run_command:'/tp %name%'>go").isStatic());
        assertTrue(this.flow.compile("100% sure").isStatic());
    }

    @Test
    @DisplayName("One template, many renders with new values")
    void renderMany() {
        Template template = this.flow.compile("<red>Hi %name%, <coins:comma> coins");
        for (int i = 0; i < 5; i++) {
            Component result = template.render(Placeholder.of("name", "P" + i, "coins", String.valueOf(i * 1000)));
            assertEquals("Hi P" + i + ", " + String.format("%,d", i * 1000) + " coins", PLAIN.serialize(result));
        }
    }

    @Test
    @DisplayName("A gradient over a placeholder is reused for the same values, parsed again for new ones")
    void gradientCachedByValues() {
        Template template = this.flow.compile("<gradient:red:blue>Welcome %name%!</gradient> <hover:show_text:'hi'>%rank%</hover>");
        Component steve = template.render(Placeholder.of("name", "Steve", "rank", "VIP"));
        assertSame(steve, template.render(Placeholder.of("name", "Steve", "rank", "VIP")), "same values: reused");
        assertEquals("Welcome Alex! VIP", PLAIN.serialize(template.render(Placeholder.of("name", "Alex", "rank", "VIP"))));
        Component gold = template.render(Placeholder.builder()
                .register("name", Component.text("Steve", NamedTextColor.GOLD)).register("rank", "VIP").build());
        assertNotSame(steve, gold, "a component value with another style is not the cached text value");
        this.flow.clearCache();
        assertNotSame(steve, template.render(Placeholder.of("name", "Steve", "rank", "VIP")), "clearCache() drops it");
    }

    @Test
    @DisplayName("format compiles a text once")
    void formatCachesTemplates() {
        AtomicInteger calls = new AtomicInteger();
        MiniMessageFlow counting = MiniMessageFlow.builder()
                .tags(TagResolver.resolver("count", (args, ctx) -> {
                    calls.incrementAndGet();
                    return Tag.selfClosingInserting(Component.text("#"));
                }))
                .build();
        assertEquals("# 0", PLAIN.serialize(counting.format("<count> %i%", Placeholder.of("i", "0"))));
        int afterCompile = calls.get(); // MiniMessage resolves a tag more than once per parse
        for (int i = 1; i < 10; i++) {
            assertEquals("# " + i, PLAIN.serialize(counting.format("<count> %i%", Placeholder.of("i", String.valueOf(i)))));
        }
        assertEquals(afterCompile, calls.get(), "compiled once, then only the value changes: no parse");
    }

    @Test
    @DisplayName("Resolver text is parsed once per text")
    void resolverTextCached() {
        AtomicInteger calls = new AtomicInteger();
        MiniMessageFlow flow = MiniMessageFlow.builder()
                .resolver((key, viewer) -> key.equals("server") ? "<gold>Lobby" : null)
                .tags(TagResolver.resolver("gold2", (args, ctx) -> {
                    calls.incrementAndGet();
                    return Tag.styling(NamedTextColor.GOLD);
                }))
                .build();
        Template template = flow.compile("On %server%");
        Component first = template.render();
        assertEquals("On Lobby", PLAIN.serialize(first));
        assertEquals(ComponentRuns.of(first), ComponentRuns.of(template.render()));
    }

    @Test
    @DisplayName("Resolvers are asked in order, after the call's values")
    void resolverOrder() {
        MiniMessageFlow flow = MiniMessageFlow.builder()
                .resolver((key, viewer) -> key.equals("a") ? "first" : null)
                .resolver((key, viewer) -> "second")
                .build();
        assertEquals("first second call", PLAIN.serialize(flow.format("%a% %b% %c%", Placeholder.of("c", "call"))));
    }

    @Test
    @DisplayName("A resolver that throws is a resolver without that key")
    void throwingResolver() {
        MiniMessageFlow flow = MiniMessageFlow.builder()
                .resolver((key, viewer) -> {
                    throw new IllegalStateException("boom");
                })
                .build();
        assertEquals("%a% <a>", PLAIN.serialize(flow.format("%a% <a>")));
    }

    @Test
    @DisplayName("Without legacy codes, & and # stay text")
    void legacyCodesOff() {
        MiniMessageFlow flow = MiniMessageFlow.builder().legacyCodes(false).build();
        assertEquals("&cHi #ff00aa", PLAIN.serialize(flow.format("&cHi #ff00aa")));
        assertEquals("Hi ", PLAIN.serialize(MiniMessageFlow.create().format("&cHi #ff00aa")).substring(0, 3));
    }

    @Test
    @DisplayName("A user tag is never a <key> placeholder, and can replace a standard tag")
    void userTags() {
        MiniMessageFlow flow = MiniMessageFlow.builder()
                .tags(TagResolver.resolver(
                        TagResolver.resolver("star", Tag.selfClosingInserting(Component.text("*"))),
                        TagResolver.resolver("red", Tag.styling(NamedTextColor.BLUE))))
                .build();
        Component result = flow.format("<star> <red>x", Placeholder.of("star", "value"));
        assertEquals("* x", PLAIN.serialize(result));
        assertEquals(NamedTextColor.BLUE, ComponentRuns.of(result).getLast().style().color());
    }

    @Test
    @DisplayName("Unknown <key> placeholders: a variant per set of unknown keys, values still swapped")
    void variants() {
        // Not <b>: that is MiniMessage's bold.
        Template template = this.flow.compile("<x> <y> %z%");
        assertEquals("1 <y> 3", PLAIN.serialize(template.render(Placeholder.builder().register("x", "1").register("z", "3").build())));
        assertEquals("<x> 2 %z%", PLAIN.serialize(template.render(Placeholder.of("y", "2"))));
        assertEquals("1 2 3", PLAIN.serialize(template.render(Placeholder.builder()
                .register("x", "1").register("y", "2").register("z", "3").build())));
    }

    @Test
    @DisplayName("A value inside a tag argument is compiled once per value")
    void inlineValues() {
        Template template = this.flow.compile("<click:run_command:'/tp %name%'>go %name%</click>");
        for (String name : new String[]{"Steve", "Alex", "Steve"}) {
            Component result = template.render(Placeholder.of("name", name));
            assertEquals("go " + name, PLAIN.serialize(result));
        }
    }

    private Engine.Form form(String message) {
        return ((CompiledTemplate) this.flow.compile(message)).baseForm();
    }

    @Test
    @DisplayName("Common messages swap markers: no parse per render")
    void fastPath() {
        for (String message : new String[]{"Hi %name%", "<red>Hi <bold>%name%</bold> &a%n.comma%", "<name> joined",
                "<click:run_command:'/help'><u>%a%</u></click> <b>%b%</b>", "%a%%b%%a%", "<lang:block.minecraft.stone> %a%"}) {
            assertInstanceOf(Engine.Form.Markers.class, this.form(message), message);
        }
    }

    @Test
    @DisplayName("Markers that can't be swapped: parsed with the values")
    void slowPath() {
        for (String message : new String[]{"<hover:show_text:'<name>'>x", "<gradient:red:blue>%a%</gradient>",
                "<rainbow><name></rainbow>", "<lang:item.x:'<name>'>"}) {
            assertInstanceOf(Engine.Form.WithValues.class, this.form(message), message);
        }
    }

    @Test
    @DisplayName("The bounded cache stays bounded")
    void boundedCache() {
        BoundedCache<Integer, Integer> cache = new BoundedCache<>(100);
        for (int i = 0; i < 10_000; i++) {
            assertEquals(i * 2, cache.get(i, k -> k * 2));
            assertTrue(cache.size() <= 100, "size " + cache.size());
        }
        assertEquals(7, cache.get(-1, k -> 7));
        assertEquals(7, cache.getIfPresent(-1));
    }

    @Test
    @DisplayName("Resolver.of gives a Placeholder's values, a component as MiniMessage")
    void resolverOf() {
        Resolver resolver = Resolver.of(Placeholder.builder()
                .register("a", "&cA")
                .register("b", Component.text("B", NamedTextColor.GOLD))
                .build());
        assertEquals("&cA", resolver.resolve("a", null));
        assertEquals("<gold>B", resolver.resolve("b", null));
        assertNull(resolver.resolve("c", null));
    }
}
