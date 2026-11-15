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
import org.jspecify.annotations.NonNull;
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
        // A template of the same text has no last render of its own: it reads the engine's cache.
        String text = template.source();
        assertSame(first, this.flow.compile(text).render(gold), "a styled value under a gradient: parsed once per value");
        this.flow.clearCache();
        assertNotSame(first, this.flow.compile(text).render(gold), "clearCache() drops it");
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
        // A value under a gradient in a hover's text, or a click inside a gradient: the old path.
        for (String message : new String[]{"<hover:show_text:'<gradient:red:blue>%name%</gradient>'>x",
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
                            public V getIfPresent(@NonNull K key) {
                                return inner.getIfPresent(key);
                            }

                            @Override
                            public @NonNull V get(@NonNull K key, java.util.function.@NonNull Function<? super K, ? extends V> loader) {
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

    @Test
    @DisplayName("A modifier of one instance: read in both syntaxes there, unknown elsewhere")
    void instanceModifiers() {
        MiniMessageFlow shouting = MiniMessageFlow.builder().modifier("Shout", (value, args) -> value.toUpperCase() + "!").build();
        Placeholder name = Placeholder.of("n", "bob");
        assertEquals("BOB! BOB! BOB", shouting.formatPlain("%n.shout% <n:shout> %n.upper%", name));
        assertEquals("%n.shout% <n:shout>", this.flow.formatPlain("%n.shout% <n:shout>", name));
        assertNull(fr.robie.minimessageflow.PlaceholderModifiers.get("shout"));
        // One of the instance replaces a shared one with the same name.
        MiniMessageFlow quiet = MiniMessageFlow.builder().modifier("upper", (value, args) -> value).build();
        assertEquals("bob", quiet.formatPlain("<n:upper>", name));
        assertThrows(IllegalArgumentException.class, () -> MiniMessageFlow.builder().modifier("a.b", (value, args) -> value));
    }

    @Test
    @DisplayName("bareHexColors(false): a #rrggbb without & is text, &#rrggbb is still a colour")
    void bareHexOff() {
        assertEquals("Issue  fixed", this.flow.formatPlain("Issue #123456 fixed", Placeholder.empty()));
        MiniMessageFlow flow = MiniMessageFlow.builder().bareHexColors(false).build();
        Component rendered = flow.format("Issue #123456 &#ff0000fixed &aok");
        assertEquals("Issue #123456 fixed ok", PLAIN.serialize(rendered));
        assertEquals(ComponentRuns.of(MiniMessage.miniMessage().deserialize("Issue #123456 <#ff0000>fixed <green>ok")), ComponentRuns.of(rendered));
    }

    @Test
    @DisplayName("A template tells how it renders and which placeholders it asks for")
    void kindAndPlaceholders() {
        assertEquals(Template.Kind.STATIC, this.flow.compile("<green>hi").kind());
        assertEquals(Template.Kind.NATIVE, this.flow.compile("<red>%a%").kind());
        assertEquals(Template.Kind.NATIVE, this.flow.compile("<click:run_command:'/x %a%'>x").kind());
        assertEquals(Template.Kind.MARKERS, this.flow.compile("<selector:@p> %a%").kind());
        assertEquals(Template.Kind.PARSED, this.flow.compile("<hover:show_text:'<name>'>x").kind());
        assertEquals(Template.Kind.NATIVE, this.flow.compile("<hover:show_text:'%a%'>x").kind());
        assertEquals(Template.Kind.PARSED, this.flow.compile("<hover:show_text:'<rainbow>%a%'>x").kind());

        Template shop = this.flow.compile("<green>%item%</green> <price:comma> <click:run_command:'/buy %id%'>x %item%</click> <b>");
        assertEquals(java.util.List.of("item", "price.comma", "id"), shop.placeholders());
        assertEquals(java.util.List.of(), this.flow.compile("<green>hi 100%").placeholders());
        assertEquals("Sword 1,250 x Sword ", shop.renderPlain(Placeholder.builder()
                .register("item", "Sword").register("price", "1250").register("id", "42").build()));
    }

    @Test
    @DisplayName("compileAll compiles a configuration, keys and order kept")
    void compileAll() {
        java.util.Map<String, String> messages = new java.util.LinkedHashMap<>();
        messages.put("welcome", "<green>Welcome %name%");
        messages.put("motd", "<gold>Hello");
        java.util.Map<String, Template> templates = this.flow.compileAll(messages);
        assertEquals(java.util.List.of("welcome", "motd"), java.util.List.copyOf(templates.keySet()));
        assertEquals("Welcome Steve", templates.get("welcome").renderPlain(Placeholder.of("name", "Steve")));
        assertTrue(templates.get("motd").isStatic());
        assertThrows(UnsupportedOperationException.class, () -> templates.put("x", templates.get("motd")));
    }

    @Test
    @DisplayName("A resolver can give a component: inserted as it is, never parsed; plain text under a modifier")
    void resolverComponents() {
        AtomicInteger textCalls = new AtomicInteger();
        MiniMessageFlow flow = MiniMessageFlow.builder().resolver(new Resolver() {
            @Override
            public String resolve(String key, net.kyori.adventure.pointer.Pointered viewer) {
                textCalls.incrementAndGet();
                return key.equals("text") ? "<green>formatted" : null;
            }

            @Override
            public Component resolveComponent(String key, net.kyori.adventure.pointer.Pointered viewer) {
                return key.equals("item") ? Component.text("<red>Sword", NamedTextColor.GOLD) : null;
            }
        }).build();
        Component rendered = flow.format("%item% %text% %item.upper%");
        assertEquals("<red>Sword formatted <RED>SWORD", PLAIN.serialize(rendered), "a component's text is never read as tags");
        assertEquals(ComponentRuns.of(MiniMessage.miniMessage().deserialize("<gold>\\<red>Sword</gold> <green>formatted</green> \\<RED>SWORD")),
                ComponentRuns.of(rendered));
        assertTrue(MiniMessage.miniMessage().serialize(flow.format("<click:run_command:'/give %item%'>x")).contains("/give redSword"),
                "in a tag argument: its plain text, without the characters that could end the tag");
    }

    @Test
    @DisplayName("A component value that throws: the error handler is told, the placeholder stays as written")
    void componentValueErrors() {
        java.util.List<String> keys = new java.util.concurrent.CopyOnWriteArrayList<>();
        MiniMessageFlow flow = MiniMessageFlow.builder()
                .resolver(Resolver.of(Placeholder.ofComponent("global", () -> {
                    throw new IllegalStateException("global down");
                })))
                .errorHandler((key, error) -> keys.add(key + ": " + error.getMessage()))
                .build();
        AtomicInteger calls = new AtomicInteger();
        Placeholder values = Placeholder.builder()
                .registerComponent("broken", () -> {
                    throw new IllegalStateException("local down");
                })
                .registerComponent("n", () -> Component.text(calls.incrementAndGet()))
                .build();
        assertEquals("%broken% %global% 1 1", flow.formatPlain("%broken% %global% %n% <n>", values));
        assertEquals(java.util.List.of("broken: local down", "global: global down"), keys);
        assertEquals(1, calls.get(), "evaluated once per render");
        assertTrue(Placeholder.ofViewerComponent("v", viewer -> Component.empty()).isViewerDependent("%v%"));
        assertFalse(Placeholder.ofComponent("v", Component::empty).isViewerDependent("%v%"));
    }

    @Test
    @DisplayName("The same values give the last render's component again; each value is still asked for")
    void lastRender() {
        MiniMessageFlow stageOne = MiniMessageFlow.builder().nativeCompiler(false).build();
        String[] messages = {
                "<green>%name%</green> has <gold>%coins.comma%</gold>", "<click:run_command:'/tp %name%'>[%coins%]",
                "<gradient:red:blue>Hi %name%!</gradient>", "<selector:@p> %name% %coins%", "<name> <nosuch> %coins%",
                "<hover:show_text:'%coins% coins'>%name%",
        };
        for (String message : messages) {
            Template template = this.flow.compile(message);
            Template expected = stageOne.compile(message);
            AtomicInteger asked = new AtomicInteger();
            Placeholder steve = Placeholder.builder().register("name", "Steve")
                    .register("coins", () -> {
                        asked.incrementAndGet();
                        return "1250";
                    }).build();
            Component first = template.render(steve);
            assertSame(first, template.render(steve), message);
            assertSame(first, template.render(Placeholder.of("name", new String("Steve"), "coins", "1250")), "equal text: " + message);
            assertEquals(message.contains("coins") ? 2 : 0, asked.get(), "a supplier is asked at each render: " + message);
            Placeholder alex = Placeholder.of("name", "Alex", "coins", "7");
            Component other = template.render(alex);
            assertNotSame(first, other, message);
            assertEquals(ComponentRuns.of(expected.render(alex)), ComponentRuns.of(other), message);
            Component gold = Component.text("Gold", NamedTextColor.GOLD);
            Component withGold = template.render(Placeholder.builder().register("name", gold).register("coins", "1").build());
            // Another instance, with another text: in a tag argument only the text is used.
            Placeholder aqua = Placeholder.builder().register("name", Component.text("Aqua", NamedTextColor.AQUA))
                    .register("coins", "1").build();
            Component withAqua = template.render(aqua);
            assertNotSame(withGold, withAqua, "another component instance: " + message);
            assertEquals(ComponentRuns.of(expected.render(aqua)), ComponentRuns.of(withAqua), message);
            // After a miss the render is kept one time in eight: the same values come back within a few renders.
            Component previous = template.render(alex);
            int renders = 1;
            for (Component next; (next = template.render(alex)) != previous; previous = next) {
                assertTrue(++renders < 500, "values sent again are kept: " + message);
                assertEquals(ComponentRuns.of(other), ComponentRuns.of(next), message);
            }
        }
    }

    @Test
    @DisplayName("A click, hover or insertion over one value is on the value's component, and shows as MiniMessage's")
    void eventOverOneValue() {
        MiniMessageFlow stageOne = MiniMessageFlow.builder().nativeCompiler(false).build();
        Component styled = Component.text("Gold", NamedTextColor.GOLD)
                .clickEvent(net.kyori.adventure.text.event.ClickEvent.suggestCommand("/own"))
                .hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(Component.text("own")))
                .insertion("own").append(Component.text(" child"));
        Placeholder[] given = {
                Placeholder.of("name", "Steve", "id", "42"), Placeholder.of("name", "", "id", ""),
                Placeholder.builder().register("name", Component.text("plain")).register("id", "7").build(),
                Placeholder.builder().register("name", styled).register("id", "7").build(),
        };
        // The whole message is the value's component: nothing around it.
        String[] leaves = {
                "<click:run_command:'/x'>%name%", "<hover:show_text:'<red>hi'><b>%name%</b></hover>", "<insert:x>%name%",
                "<red><click:run_command:'/x'><hover:show_text:'hi'><u>%name%",
                "<click:run_command:'/tp %id%'>%name%</click>", "<insert:'%id%'><green>%name%",
                "<hover:show_text:'<gray>id %id%'>%name%", "<click:open_url:'https://x.com/'><hover:show_text:'%id%'>%name%",
        };
        // An event of the same kind on the value's own tag, or something besides the value: still as MiniMessage's.
        String[] others = {
                "<click:run_command:'/a %id%'><click:run_command:'/b'>%name%", "<hover:show_text:'%id%'><hover:show_text:'in'>%name%",
                "<insert:'%id%'><insert:in>%name%", "<click:run_command:'/a'><click:run_command:'/b %id%'>%name%",
                "<click:run_command:'/x'>%name% </click>", "<click:run_command:'/x %id%'>[%name%]", "<hover:show_text:'%id%'>%name%%id%",
                "<click:run_command:'/x'><key:key.jump>%name%", "a <click:run_command:'/x'>%name%</click> b <hover:show_text:'%id%'>%name%",
                // Two events built at render: the inner one is on the value, the outer one around it.
                "<click:run_command:'/x %id%'><hover:show_text:'%id%'>%name%",
        };
        for (boolean leaf : new boolean[]{true, false}) {
            for (String message : leaf ? leaves : others) {
                Template template = this.flow.compile(message);
                assertEquals(Template.Kind.NATIVE, template.kind(), message);
                Template expected = stageOne.compile(message);
                for (Placeholder placeholder : given) {
                    Component rendered = template.render(placeholder);
                    assertEquals(ComponentRuns.of(expected.render(placeholder)), ComponentRuns.of(rendered),
                            message + " with " + placeholder.getMap());
                    if (leaf && placeholder != given[3]) {
                        assertTrue(rendered.children().isEmpty(), message + ": " + rendered);
                    }
                }
            }
        }
    }

    @Test
    @DisplayName("A value in a hover's text or a translation argument is placed at render, as MiniMessage would read it")
    void valuesInHoverAndTranslation() {
        MiniMessageFlow stageOne = MiniMessageFlow.builder().nativeCompiler(false).build();
        String[] messages = {
                "<hover:show_text:'<gray>Balance: <gold>%coins%'>%name%</hover> joined",
                "<hover:show_text:'%coins% coins, <b>%name%</b>!'><red>x",
                "<hover:show_text:%coins%>x</hover> y",
                "<lang:chat.type.text:'%name%':'<green>%coins%'> after",
                "<lang_or:some.key:'fallback':'<b>%name%</b> has %coins%'>",
                "<tr:some.key:%name%:fixed>",
        };
        String[][] values = {
                {"Steve", "1,250"}, {"", ""}, {"<red>inject", "&cred"}, {"a:b", "c:d"}, {"#ff0000", "#00ff00"},
                {"it's \"quoted\"", "100%"}, {"\back", "x y"},
        };
        for (String message : messages) {
            Template template = this.flow.compile(message);
            assertEquals(Template.Kind.NATIVE, template.kind(), message);
            Template expected = stageOne.compile(message);
            for (String[] pair : values) {
                Placeholder given = Placeholder.of("name", pair[0], "coins", pair[1]);
                assertEquals(ComponentRuns.of(expected.render(given)), ComponentRuns.of(template.render(given)),
                        message + " with " + pair[0] + ", " + pair[1]);
            }
            assertEquals(ComponentRuns.of(expected.render()), ComponentRuns.of(template.render()), message + " without values");
            Placeholder component = Placeholder.builder().register("name", Component.text("Gold", NamedTextColor.GOLD))
                    .register("coins", "5").build();
            assertEquals(ComponentRuns.of(expected.render(component)), ComponentRuns.of(template.render(component)),
                    message + " with a component");
        }
        // What a value could turn into a legacy code or another tag stays with MiniMessage.
        for (String message : new String[]{
                "<hover:show_text:'Issue #%coins%'>x", "<hover:show_text:'&%coins%'>x",
                "<hover:show_text:'<gradient:red:blue>%name%</gradient>'>x", "<hover:show_text:'<%name%>hi'>x"}) {
            Template template = this.flow.compile(message);
            Placeholder given = Placeholder.of("name", "red", "coins", "123456");
            assertEquals(ComponentRuns.of(stageOne.compile(message).render(given)), ComponentRuns.of(template.render(given)), message);
        }
    }

    @Test
    @DisplayName("Values set by key render as the same values in a Placeholder")
    void valuesByKey() {
        MiniMessageFlow.Builder builder = MiniMessageFlow.builder()
                .resolver(Resolver.of(Placeholder.of("server", "<gold>Lobby", "coins", "7")));
        fr.robie.minimessageflow.ValueKey name = builder.key("name");
        fr.robie.minimessageflow.ValueKey coins = builder.key("coins");
        fr.robie.minimessageflow.ValueKey item = builder.key("item");
        assertSame(name, builder.key("name"), "a name is declared once");
        MiniMessageFlow flow = builder.build();
        fr.robie.minimessageflow.Values values = flow.newValues();

        String[] messages = {
                "<green>%name%</green> has <gold>%coins.comma%</gold> on %server%",
                "<name> <coins:comma> <item>",
                "<click:run_command:'/pay %name% %coins%'>[pay %name%]</click>",
                "<hover:show_text:'<gray>%coins.comma% coins'>%name%</hover> %item%",
                "<gradient:red:blue>Welcome %name%!</gradient>",
                "<selector:@p> %name% %coins%",
                "%name% %name.upper% %unknown% %coins.nosuch%",
        };
        Component sword = Component.text("Sword", NamedTextColor.AQUA);
        for (String message : messages) {
            Template template = flow.compile(message);
            values.clear().set(name, "Steve").set(coins, 1250).set(item, sword);
            assertEquals(ComponentRuns.of(template.render(Placeholder.builder().register("name", "Steve")
                            .register("coins", "1250").register("item", sword).build())),
                    ComponentRuns.of(template.render(values)), message);
            // A key that is not set is a key the call does not give: the resolvers are asked, or it stays as written.
            values.unset(coins).unset(item);
            assertEquals(ComponentRuns.of(template.render(Placeholder.of("name", "Steve"))),
                    ComponentRuns.of(template.render(values)), message + ", coins and item unset");
            values.set(name, (String) null);
            assertEquals(ComponentRuns.of(template.render(Placeholder.of("name", ""))),
                    ComponentRuns.of(template.render(values)), message + ", empty name");
        }
        assertTrue(values.isSet(name));
        assertFalse(values.clear().isSet(name));

        // Values of another instance are refused, as are its keys.
        MiniMessageFlow.Builder otherBuilder = MiniMessageFlow.builder();
        fr.robie.minimessageflow.ValueKey otherKey = otherBuilder.key("name");
        MiniMessageFlow other = otherBuilder.build();
        assertThrows(IllegalArgumentException.class, () -> values.set(otherKey, "x"));
        Template dynamic = flow.compile("%name%");
        assertThrows(IllegalArgumentException.class, () -> dynamic.render(other.newValues()));
        // The copy for a template that does not read Values.
        values.set(name, "Steve").set(item, sword);
        assertEquals("Steve", values.toPlaceholder().parse("%name%"));
        assertEquals(2, values.toPlaceholder().getMap().size());
    }

    @Test
    @DisplayName("A gradient gives the same colours whatever was rendered before it, at every length")
    void gradientLengths() {
        MiniMessageFlow stageOne = MiniMessageFlow.builder().nativeCompiler(false).build();
        String[] messages = {
                "<gradient:red:blue>Welcome %name%!</gradient>",
                "<gradient:red:blue:green:0.3>%name%</gradient> after",
                "<rainbow>%name% and <b>%other%</b> end</rainbow>",
                "<rainbow:!2><i>%name%</i></rainbow>",
                "<gradient:#ff0000:#0000ff>a%name%b%other%c</gradient>",
                "<yellow><gradient:red:blue><u>pre </u>%name%</gradient> tail",
        };
        String[] names = {"", "S", "Steve", "\u00e9t\u00e9", "a\uD83D\uDE00b", "x".repeat(40), "y".repeat(90), "Steve", "S"};
        for (String message : messages) {
            Template template = this.flow.compile(message);
            assertEquals(Template.Kind.NATIVE, template.kind(), message);
            Template expected = stageOne.compile(message);
            // Twice over the same lengths: the second pass reads what the first one kept.
            for (int pass = 0; pass < 2; pass++) {
                for (String name : names) {
                    Placeholder given = Placeholder.of("name", name, "other", "o" + name);
                    assertEquals(ComponentRuns.of(expected.render(given)), ComponentRuns.of(template.render(given)),
                            message + " with a name of " + name.length() + " characters, pass " + pass);
                }
            }
        }
    }

    @Test
    @DisplayName("A constant is formatted once; a message with only constants is static")
    void constants() {
        MiniMessageFlow flow = MiniMessageFlow.builder()
                .constant("prefix", "<gold>[Shop]")
                .constant("server", "lobby")
                .constant("star", Component.text("*", NamedTextColor.YELLOW))
                .build();
        // The same values from a resolver: what a constant must show.
        MiniMessageFlow resolved = MiniMessageFlow.builder()
                .resolver(Resolver.of(Placeholder.builder().register("prefix", "<gold>[Shop]").register("server", "lobby")
                        .register("star", Component.text("*", NamedTextColor.YELLOW)).build()))
                .build();
        String[] onlyConstants = {
                "%prefix% Welcome to <b>%server%</b> %star%",
                "<prefix> <green>ok <server:upper>",
                "<click:run_command:'/server %server%'>[%server.upper%]</click>",
                "<hover:show_text:'on %server%'>%prefix%</hover>",
                "<gradient:red:blue>%server%</gradient>",
        };
        for (String message : onlyConstants) {
            Template template = flow.compile(message);
            assertEquals(Template.Kind.STATIC, template.kind(), message);
            assertTrue(template.isStatic(), message);
            assertSame(template.render(), template.render(Placeholder.of("server", "other")), message);
            assertEquals(ComponentRuns.of(resolved.compile(message).render()), ComponentRuns.of(template.render()), message);
        }
        // With another placeholder the message still changes, and the constants are read without a lookup.
        for (String message : new String[]{"%prefix% Hi %name% on %server.upper%", "<prefix> <name>",
                "<click:run_command:'/tp %name% %server%'>%star%</click>", "%prefix% %server.nosuch% %name%"}) {
            Template template = flow.compile(message);
            assertFalse(template.isStatic(), message);
            Placeholder name = Placeholder.of("name", "Steve");
            assertEquals(ComponentRuns.of(resolved.compile(message).render(name)), ComponentRuns.of(template.render(name)), message);
        }
        // The colour of a constant's text ends with it.
        assertEquals(ComponentRuns.of(MiniMessage.miniMessage().deserialize("<gold>[Shop]</gold> plain")),
                ComponentRuns.of(flow.compile("%prefix% plain").render()));
    }

    @Test
    @DisplayName("A message that is not native tells why")
    void fallbackReason() {
        assertNull(this.flow.compile("<green>hi").fallbackReason());
        assertNull(this.flow.compile("<red>%a%").fallbackReason());
        assertNull(this.flow.compile("<hover:show_text:'%a%'>x").fallbackReason());
        assertEquals("the tag <selector>", this.flow.compile("<red>hi <selector:@p> %a%").fallbackReason());
        assertEquals("the tag <hover>", this.flow.compile("<hover:show_text:'<rainbow>%a%'>x").fallbackReason());
        assertNotNull(this.flow.compile("a < b > %c%").fallbackReason());
        assertEquals("the native compiler is off",
                MiniMessageFlow.builder().nativeCompiler(false).build().compile("Hi %name%").fallbackReason());
    }

    @Test
    @DisplayName("Values hold computed and viewer values, and format takes them")
    void computedValues() {
        MiniMessageFlow.Builder builder = MiniMessageFlow.builder();
        fr.robie.minimessageflow.ValueKey time = builder.key("time");
        fr.robie.minimessageflow.ValueKey who = builder.key("who");
        fr.robie.minimessageflow.ValueKey item = builder.key("item");
        MiniMessageFlow flow = builder.build();
        fr.robie.minimessageflow.Values values = flow.newValues();

        AtomicInteger calls = new AtomicInteger();
        values.setComputed(time, () -> "t" + calls.incrementAndGet())
                .setForViewer(who, viewer -> viewer == null ? "nobody" : "seen")
                .set(item, fr.robie.minimessageflow.PlaceholderValue.ofDynamicComponent(() -> Component.text("Sword", NamedTextColor.AQUA)));
        Template template = flow.compile("<red>%time%</red> again %time% <click:run_command:'/at %time%'>[%who%]</click> <item>");
        assertEquals("t1 again t1 [] Sword", PLAIN.serialize(template.render(values)), "without a viewer, a viewer value is empty");
        assertEquals(1, calls.get(), "asked once per render, wherever it is written");
        assertEquals("t2 again t2 [] Sword", PLAIN.serialize(template.render(values)), "asked again at the next render");
        net.kyori.adventure.pointer.Pointered viewer = new net.kyori.adventure.pointer.Pointered() {
        };
        assertEquals("t3 again t3 [seen] Sword", PLAIN.serialize(template.render(values, viewer)));
        assertEquals("T4", PLAIN.serialize(flow.compile("%time.upper%").render(values)), "modifiers read a computed value");
        assertEquals(ComponentRuns.of(template.render(values.toPlaceholder(), viewer)).size(),
                ComponentRuns.of(template.render(values, viewer)).size(), "the copy as a Placeholder renders the same shape");

        // A value that throws shows as written, and the handler is told, as with a Placeholder.
        values.setComputed(time, () -> {
            throw new IllegalStateException("boom");
        });
        assertEquals("%time%", PLAIN.serialize(flow.compile("%time%").render(values)));

        values.clear().set(who, "Steve");
        assertEquals("Hi Steve", PLAIN.serialize(flow.format("Hi %who%", values)));
        assertEquals("Hi Steve", PLAIN.serialize(flow.format("Hi %who%", values, viewer)));
    }

    @Test
    @DisplayName("Text and text values of one style render as one component; a component value keeps its own")
    void joinedText() {
        MiniMessageFlow reference = MiniMessageFlow.builder().nativeCompiler(false).build();
        String[] messages = {"<green>Welcome %name%!", "<click:run_command:'/tp %name%'>[tp %name%]",
                "<red><click:run_command:/help>[%name% and %rank%]</click> after", "<hover:show_text:'<gray>%rank%'>[%name%]</hover>",
                "<insert:%name%>%rank% x %name% y %rank%</insert>", "Hello <red>%name%", "%name%%rank%",
                "<click:suggest_command:/w %name%><click:suggest_command:/b>%rank%!</click></click>"};
        Placeholder text = Placeholder.of("name", "Steve", "rank", "VIP");
        Placeholder component = Placeholder.builder()
                .register("name", Component.text("Steve", NamedTextColor.GOLD)).register("rank", "VIP").build();
        for (String message : messages) {
            Template template = this.flow.compile(message);
            assertEquals(Template.Kind.NATIVE, template.kind(), message);
            for (Placeholder values : new Placeholder[]{text, component, Placeholder.of("rank", "VIP")}) {
                assertEquals(ComponentRuns.of(reference.compile(message).render(values)), ComponentRuns.of(template.render(values)),
                        message + " with " + values.getMap());
            }
        }
        Component welcome = this.flow.compile("<green>Welcome %name%!").render(text);
        assertEquals(Component.text("Welcome Steve!", NamedTextColor.GREEN), welcome, "one component");
        Component tp = this.flow.compile("<click:run_command:'/tp %name%'>[tp %name%]").render(text);
        assertTrue(tp.children().isEmpty(), "the click is on the text itself: " + tp);
        assertEquals("[tp Steve]", PLAIN.serialize(tp));
        Component hello = this.flow.compile("Hello <red>%name%").render(text);
        assertEquals("Hello ", ((net.kyori.adventure.text.TextComponent) hello).content(), "the first text is the parent");
        assertEquals(1, hello.children().size());
    }

    @Test
    @DisplayName("The last render is given again only while every value, written once or more, is the same")
    void lastRenderMatchesEachValue() {
        Template template = this.flow.compile("<red>%a%</red> %b% <click:run_command:'/x %a% %c%'>%a% %c%</click>");
        String[][] sends = {{"1", "2", "3"}, {"1", "2", "3"}, {"1", "9", "3"}, {"1", "9", "4"}, {"5", "9", "4"},
                {"5", "9", "4"}, {"1", "2", "3"}};
        for (String[] send : sends) {
            Placeholder values = Placeholder.of(java.util.Map.of("a", send[0], "b", send[1], "c", send[2]));
            for (int i = 0; i < 3; i++) {
                Component rendered = template.render(values);
                String expected = send[0] + " " + send[1] + " " + send[0] + " " + send[2];
                assertEquals(expected, PLAIN.serialize(rendered));
                assertEquals(net.kyori.adventure.text.event.ClickEvent.runCommand("/x " + send[0] + " " + send[2]), clickOf(rendered), expected);
            }
        }
        // A key without a value shows as written, also when the last render had one.
        assertEquals("1 %b% 1 3", PLAIN.serialize(template.render(Placeholder.of("a", "1", "c", "3"))));
        assertEquals("1 2 1 3", PLAIN.serialize(template.render(Placeholder.of(java.util.Map.of("a", "1", "b", "2", "c", "3")))));
    }

    private static Object clickOf(Component component) {
        if (component.clickEvent() != null) {
            return component.clickEvent();
        }
        for (Component child : component.children()) {
            Object value = clickOf(child);
            if (value != null) {
                return value;
            }
        }
        return null;
    }
}
