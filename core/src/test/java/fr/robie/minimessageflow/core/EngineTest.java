package fr.robie.minimessageflow.core;

import fr.robie.minimessageflow.MiniMessageFlow;
import fr.robie.minimessageflow.Placeholder;
import fr.robie.minimessageflow.Resolver;
import fr.robie.minimessageflow.Template;
import fr.robie.minimessageflow.testkit.ComponentRuns;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
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
    @DisplayName("A gradient over a placeholder is coloured at render; a styled value is parsed with MiniMessage, cached")
    void gradientOverValues() {
        Template template = this.flow.compile("<gradient:red:blue>Welcome %name%!</gradient> <hover:show_text:'hi'>%rank%</hover>");
        assertInstanceOf(Engine.Form.Native.class, ((CompiledTemplate) template).baseForm());
        assertEquals("Welcome Alex! VIP", PLAIN.serialize(template.render(Placeholder.of("name", "Alex", "rank", "VIP"))));
        Placeholder gold = Placeholder.builder()
                .register("name", Component.text("Steve", NamedTextColor.GOLD)).register("rank", "VIP").build();
        Component first = template.render(gold);
        assertSame(first, template.render(gold), "a styled value under a gradient: parsed once per value");
        this.flow.clearCache();
        assertNotSame(first, template.render(gold), "clearCache() drops it");
    }

    @Test
    @DisplayName("A value in a click or insert argument: compiled once, the event built at render")
    void eventArgumentsCompiledOnce() {
        for (String message : new String[]{"<click:run_command:'/tp %name%'>[tp]</click>", "<insert:%name%>x</insert> %b%",
                "<click:run_command:'/a%name%'>x", "<click:run_command:'/w %name%1'>x", "<click:run_command:'/w #%name%%name%'>x",
                "<red><click:suggest_command:'/w %name% '><gradient:red:blue>%name%</gradient></click>"}) {
            CompiledTemplate template = (CompiledTemplate) this.flow.compile(message);
            assertNotNull(template.inlineForm(), message);
        }
        // A value in a hover or a click inside a gradient: the old path.
        for (String message : new String[]{"<hover:show_text:'%name%'>x",
                "<gradient:red:blue><click:suggest_command:'/w %name% '>%name%</click></gradient>"}) {
            assertNull(((CompiledTemplate) this.flow.compile(message)).inlineForm(), message);
        }
    }

    @Test
    @DisplayName("Legacy codes are not read in a click or insert tag: a URL keeps its & and #")
    void eventArgumentsKeepLegacyCharacters() {
        MiniMessage mini = MiniMessage.miniMessage();
        for (boolean nativeCompiler : new boolean[]{true, false}) {
            MiniMessageFlow flow = MiniMessageFlow.builder().nativeCompiler(nativeCompiler).build();
            assertEquals(mini.deserialize("<click:open_url:'https://x.com/?a=1&b=2&c=3#abcdef'><red>link"),
                    flow.format("<click:open_url:'https://x.com/?a=1&b=2&c=3#abcdef'>&clink").compact());
            assertEquals(mini.deserialize("<click:copy_to_clipboard:'#ff0000'>copy"),
                    flow.format("<CLICK:copy_to_clipboard:\"#ff0000\">copy").compact());
            assertEquals(mini.deserialize("<insert:'&c#ff0000'>x"), flow.format("<insert:'&c#ff0000'>x").compact());
            assertEquals(mini.deserialize("<click:run_command:'/color #ff0000 &a'>x"),
                    flow.format("<click:run_command:'/color %c% &a'>x", Placeholder.of("c", "#ff0000")).compact());
            // A hover's text is formatted text: codes are still read there.
            assertEquals(ComponentRuns.of(mini.deserialize("<hover:show_text:'<red>hi'>x")),
                    ComponentRuns.of(flow.format("<hover:show_text:'&chi'>x")));
        }
    }

    @Test
    @DisplayName("A < that opens no tag is text: the values after it are whole, and compiled once")
    void strayTagStart() {
        Placeholder name = Placeholder.of("name", "O'Brien & <Co>");
        for (String message : new String[]{"I <3 you %name%", "5 < 6, hi %name%", "<red>a <- b</red> %name%", "x <3 %name% <b>y"}) {
            CompiledTemplate template = (CompiledTemplate) this.flow.compile(message);
            assertNull(template.inlineForm(), message);
            assertFalse(template.baseForm() instanceof Engine.Form.WithValues, message);
            assertTrue(PLAIN.serialize(template.render(name)).contains("O'Brien & <Co>"), message);
        }
        Component styled = this.flow.format("I <3 you %name%", Placeholder.of("name", Component.text("Steve", NamedTextColor.RED)));
        assertTrue(MiniMessage.miniMessage().serialize(styled).contains("<red>Steve"));
    }

    @Test
    @DisplayName("A key is evaluated once per render, wherever it is written")
    void valueEvaluatedOnce() {
        for (String message : new String[]{"<click:run_command:'/x %n%'>%n% %n%</click>",
                "<click:run_command:'/x %n% %n%'><insert:'%n%'>y", "<hover:show_text:'%n%'>%n% <n>"}) {
            AtomicInteger calls = new AtomicInteger();
            Component rendered = this.flow.compile(message).render(Placeholder.of("n", () -> String.valueOf(calls.incrementAndGet())));
            assertEquals(1, calls.get(), message);
            assertFalse(MiniMessage.miniMessage().serialize(rendered).contains("2"), message);
        }
    }

    @Test
    @DisplayName("A resolver or a value that throws: the error handler is told, the render goes on")
    void errorHandler() {
        java.util.List<String> keys = new java.util.concurrent.CopyOnWriteArrayList<>();
        MiniMessageFlow flow = MiniMessageFlow.builder()
                .resolver((key, viewer) -> {
                    if (key.equals("broken")) {
                        throw new IllegalStateException("hook down");
                    }
                    return null;
                })
                .resolver(Resolver.of(Placeholder.of("global", () -> {
                    throw new IllegalStateException("global down");
                })))
                .errorHandler((key, error) -> {
                    keys.add(key + ": " + error.getMessage());
                    throw new IllegalStateException("a handler that throws changes nothing");
                })
                .build();
        Component rendered = flow.format("a %broken% b %local% c %global% d", Placeholder.of("local", () -> {
            throw new IllegalStateException("local down");
        }));
        assertEquals("a %broken% b %local% c %global% d", PLAIN.serialize(rendered));
        assertEquals(java.util.List.of("broken: hook down", "local: local down", "global: global down"), keys);
        // The default handler logs instead of throwing.
        assertEquals("%x%", PLAIN.serialize(MiniMessageFlow.create().format("%x%", Placeholder.of("x", () -> {
            throw new IllegalStateException("logged once");
        }))));
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
    @DisplayName("Messages of native tags compile without MiniMessage")
    void nativePath() {
        for (String message : new String[]{"Hi %name%", "<red>Hi <bold>%name%</bold> &a%n.comma%", "<name> joined",
                "<click:run_command:'/help'><u>%a%</u></click> <b>%b%</b>", "%a%%b%%a%",
                "<green>%item%</green> for <gold>%price.comma%</gold> <click:run_command:'/buy 42'>[Buy]",
                "<hover:show_text:'<red>Info'><!bold>%a%</hover> <#ff00aa>x<reset>%b%", "&c[%rank%] &f%name%",
                "<lang:block.minecraft.stone> %a%", "<font:uniform>%a%</font>", "<key:key.jump> %a%", "<transition:red:blue:0.5>%a%"}) {
            assertInstanceOf(Engine.Form.Native.class, this.form(message), message);
        }
    }

    @Test
    @DisplayName("Shadows are native where the bridge builds them (Adventure 5), MiniMessage's on 4.x")
    void shadowByBridge() {
        for (String message : new String[]{"<shadow:red>%a%</shadow> <!shadow>x", "<sprite:item/diamond> %a% <head> <head:Notch>"}) {
            Engine.Form form = this.form(message);
            if (AdventureBridges.current().major() >= 5) {
                assertInstanceOf(Engine.Form.Native.class, form, message);
            } else {
                assertFalse(form instanceof Engine.Form.Native, message + ": " + form);
            }
        }
        Engine.Form custom = this.form("<click:custom:'ns:key':'{a:1b}'>%a%");
        assertEquals(AdventureBridges.current().major() >= 5, custom instanceof Engine.Form.Native, "custom click: " + custom);
        for (String message : new String[]{"<bold:off>%a% <i:yes>x", "<click:RUN_COMMAND:'/x'>%a%", "<click:change_page:2>%a%",
                "<hover:show_item:diamond>%a%", "<hover:show_item:'minecraft:diamond':3>%a%",
                "<hover:show_entity:pig:069a79f4-44e9-4726-a5be-fca90e38aaf5:'<red>Zed'>%a%"}) {
            assertInstanceOf(Engine.Form.Native.class, this.form(message), message);
        }
        // Forms the versions read differently stay with MiniMessage everywhere.
        assertFalse(this.form("<head:069a79f4-44e9-4726-a5be-fca90e38aaf5> %a%") instanceof Engine.Form.Native);
        assertFalse(this.form("<head:on> %a%") instanceof Engine.Form.Native);
    }

    @Test
    @DisplayName("Messages with other tags swap markers in MiniMessage's tree: no parse per render either")
    void markerPath() {
        for (String message : new String[]{"<selector:@p> %a%", "<score:steve:kills> %a%", "a < b > %c%"}) {
            assertInstanceOf(Engine.Form.Markers.class, this.form(message), message);
        }
        MiniMessageFlow noNative = MiniMessageFlow.builder().nativeCompiler(false).build();
        assertInstanceOf(Engine.Form.Markers.class, ((CompiledTemplate) noNative.compile("Hi %name%")).baseForm());
    }

    @Test
    @DisplayName("Markers that can't be swapped: parsed with the values")
    void slowPath() {
        for (String message : new String[]{"<hover:show_text:'<name>'>x", "<lang:item.x:'<name>'>"}) {
            assertInstanceOf(Engine.Form.WithValues.class, this.form(message), message);
        }
    }

    @Test
    @DisplayName("A cache factory makes every cache, with the builder's sizes, and they are used")
    void cacheFactory() {
        java.util.Map<fr.robie.minimessageflow.cache.CacheFactory.Kind, Integer> sizes = new java.util.EnumMap<>(fr.robie.minimessageflow.cache.CacheFactory.Kind.class);
        AtomicInteger loads = new AtomicInteger();
        MiniMessageFlow flow = MiniMessageFlow.builder()
                .templateCacheSize(7)
                .valueCacheSize(9)
                .resolver((key, viewer) -> key.equals("g") ? "<gold>G" : null)
                .cacheFactory(new fr.robie.minimessageflow.cache.CacheFactory() {
                    @Override
                    public <K, V> fr.robie.minimessageflow.cache.@org.jetbrains.annotations.NotNull Cache<K, V> create(
                            @org.jetbrains.annotations.NotNull Kind kind, int maximumSize) {
                        sizes.put(kind, maximumSize);
                        BoundedCache<K, V> inner = new BoundedCache<>(maximumSize);
                        return new fr.robie.minimessageflow.cache.Cache<>() {
                            @Override
                            public V getIfPresent(K key) {
                                return inner.getIfPresent(key);
                            }

                            @Override
                            public V get(K key, java.util.function.Function<? super K, ? extends V> loader) {
                                return inner.get(key, k -> {
                                    loads.incrementAndGet();
                                    return loader.apply(k);
                                });
                            }

                            @Override
                            public void clear() {
                                inner.clear();
                            }
                        };
                    }
                })
                .build();
        assertEquals(java.util.Map.of(fr.robie.minimessageflow.cache.CacheFactory.Kind.TEMPLATES, 7,
                fr.robie.minimessageflow.cache.CacheFactory.Kind.RESOLVER_TEXTS, 9,
                fr.robie.minimessageflow.cache.CacheFactory.Kind.PARSED_WITH_VALUES, 9,
                fr.robie.minimessageflow.cache.CacheFactory.Kind.INLINE_TEXTS, 9), sizes);
        assertEquals("Hi G", PLAIN.serialize(flow.format("Hi %g%")));
        int afterFirst = loads.get(); // the text compiled, the resolver text parsed
        assertEquals("Hi G", PLAIN.serialize(flow.format("Hi %g%")));
        assertEquals(afterFirst, loads.get(), "the second format only hits");
        assertTrue(afterFirst >= 2);
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
