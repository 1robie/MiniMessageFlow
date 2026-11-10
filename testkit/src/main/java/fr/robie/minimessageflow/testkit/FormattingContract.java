package fr.robie.minimessageflow.testkit;

import fr.robie.minimessageflow.Placeholder;
import net.kyori.adventure.identity.Identity;
import net.kyori.adventure.pointer.Pointered;
import net.kyori.adventure.pointer.Pointers;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What formatting does, as MessageFlow 1.1 defines it: every engine's tests extend this class.
 * <p>
 * Ported from MessageFlow's {@code AdventureFormattingTest}, {@code SharedFormattingTest} and
 * {@code TemplateCacheTest} (their formatting parts; sending, Bukkit and cache internals stay in MessageFlow or in
 * the engine's own tests). A deliberate behaviour change of the library is an edit of this class, in the same
 * commit, so it is always visible.
 */
public abstract class FormattingContract {

    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    /**
     * The engine under test.
     *
     * @param globals placeholders the engine resolves itself, after the local ones (MessageFlow's global
     *                placeholders)
     */
    protected abstract @NotNull TestFormatter formatter(@NotNull Placeholder globals);

    private @NotNull TestFormatter formatter() {
        return this.formatter(Placeholder.empty());
    }

    /**
     * A viewer whose {@link Identity#NAME} is {@code name}.
     */
    protected static @NotNull Pointered viewer(@NotNull String name) {
        Pointers pointers = Pointers.builder().withStatic(Identity.NAME, name).build();
        return new Pointered() {
            @Override
            public @NotNull Pointers pointers() {
                return pointers;
            }
        };
    }

    private static @NotNull String name(@NotNull Pointered viewer) {
        return viewer.get(Identity.NAME).orElse("?");
    }

    protected static @NotNull List<Component> flatten(@NotNull Component component) {
        List<Component> all = new ArrayList<>();
        all.add(component);
        component.children().forEach(child -> all.addAll(flatten(child)));
        return all;
    }

    /**
     * The first click event. Typed {@code Object} on purpose: {@code ClickEvent} is generic from Adventure 5 and its
     * {@code value()} is gone, so tests compare whole events built with {@code ClickEvent.runCommand}, which every
     * version has.
     */
    private static @NotNull Object click(@NotNull Component component) {
        return flatten(component).stream().map(Component::clickEvent).filter(Objects::nonNull).findFirst().orElseThrow();
    }

    // --- values -------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("Tags and legacy codes in a placeholder value stay text")
    void valueCannotInjectTags() {
        String evil = "<click:run_command:'/op me'>click</click>&4red";
        Component result = this.formatter().format("Hello %name%!", Placeholder.of("name", evil), null);
        assertEquals("Hello " + evil + "!", PLAIN.serialize(result));
        assertTrue(flatten(result).stream().allMatch(c -> c.clickEvent() == null), "no click event");
        assertTrue(flatten(result).stream().noneMatch(c -> NamedTextColor.DARK_RED.equals(c.color())), "no colour");
    }

    @Test
    @DisplayName("The message keeps its own formatting around a placeholder")
    void messageFormattingStillWorks() {
        Component result = this.formatter().format("&aHello <bold>%name%</bold>", Placeholder.of("name", "Steve"), null);
        assertEquals("Hello Steve", PLAIN.serialize(result));
        List<ComponentRuns.Run> runs = ComponentRuns.of(result);
        assertEquals(2, runs.size(), runs::toString);
        assertEquals(NamedTextColor.GREEN, runs.get(0).style().color());
        assertEquals(NamedTextColor.GREEN, runs.get(1).style().color());
        assertEquals("Steve", runs.get(1).content());
        assertEquals(TextDecoration.State.TRUE, runs.get(1).style().decoration(TextDecoration.BOLD));
    }

    @Test
    @DisplayName("A viewer placeholder gets the viewer")
    void viewerPlaceholderGetsViewer() {
        Component result = this.formatter().format("Hi %who%", Placeholder.of("who", FormattingContract::name), viewer("Alex"));
        assertEquals("Hi Alex", PLAIN.serialize(result));
    }

    @Test
    @DisplayName("A component placeholder keeps its style")
    void componentPlaceholder() {
        Component value = Component.text("Furnace", NamedTextColor.GOLD);
        Component result = this.formatter().format("Machine: %machine%", Placeholder.of("machine", value), null);
        assertEquals("Machine: Furnace", PLAIN.serialize(result));
        assertTrue(flatten(result).stream().anyMatch(c -> NamedTextColor.GOLD.equals(c.color())));
    }

    @Test
    @DisplayName("Unknown placeholders stay as written; a repeated key gets its value each time")
    void unknownAndRepeated() {
        Component result = this.formatter().format("%a% %b% %a%", Placeholder.of("a", "1"), null);
        assertEquals("1 %b% 1", PLAIN.serialize(result));
    }

    @Test
    @DisplayName("A % that opens no placeholder is kept")
    void lonePercent() {
        Component result = this.formatter().format("100% sure %player%", Placeholder.of("player", "Steve"), null);
        assertEquals("100% sure Steve", PLAIN.serialize(result));
    }

    @Test
    @DisplayName("A message used again takes the new values")
    void newValuesEachTime() {
        String message = "<red>Hi %name%, you have %coins% coins";
        for (int i = 0; i < 3; i++) {
            Placeholder values = Placeholder.of("name", "P" + i, "coins", String.valueOf(i * 10));
            assertEquals("Hi P" + i + ", you have " + (i * 10) + " coins", PLAIN.serialize(this.formatter().format(message, values, null)));
        }
    }

    @Test
    @DisplayName("Placeholders under a gradient and in a hover get their values")
    void gradientAndHover() {
        String message = "<gradient:red:blue>Welcome %name%!</gradient> <hover:show_text:'hi'>%rank%</hover>";
        TestFormatter formatter = this.formatter();
        assertEquals("Welcome Steve! VIP", PLAIN.serialize(formatter.format(message, Placeholder.of("name", "Steve", "rank", "VIP"), null)));
        assertEquals("Welcome Alex! VIP", PLAIN.serialize(formatter.format(message, Placeholder.of("name", "Alex", "rank", "VIP"), null)));
    }

    // --- inside tags --------------------------------------------------------------------------------------------

    @Test
    @DisplayName("Inside a tag, a placeholder is plain text that cannot close the tag")
    void placeholderInsideTag() {
        Component result = this.formatter().format("<click:run_command:'/tp %name%'>go</click>",
                Placeholder.of("name", "Steve'><red>x"), null);
        assertEquals(ClickEvent.runCommand("/tp Steveredx"), click(result), "the quote and brackets are dropped, so no tag is closed");
        assertEquals("go", PLAIN.serialize(result));
    }

    @Test
    @DisplayName("Inside a tag, a value can't be read again as a global placeholder or a colour")
    void insideTagNotResolvedAgain() {
        Component result = this.formatter(Placeholder.of("server", "Lobby"))
                .format("<click:run_command:'/msg %target%'>go</click>", Placeholder.of("target", "%server%&a§b"), null);
        assertEquals(ClickEvent.runCommand("/msg serverab"), click(result));
    }

    // --- global placeholders ------------------------------------------------------------------------------------

    @Test
    @DisplayName("Local and global placeholders together")
    void localAndGlobal() {
        Component result = this.formatter(Placeholder.of("server", "Lobby"))
                .format("<red>Hi %name% on %server%", Placeholder.of("name", "all"), null);
        assertEquals("Hi all on Lobby", PLAIN.serialize(result));
    }

    @Test
    @DisplayName("A viewer-specific global gets the viewer")
    void viewerGlobal() {
        TestFormatter formatter = this.formatter(Placeholder.of("me", FormattingContract::name));
        assertEquals("I am Alex", PLAIN.serialize(formatter.format("I am %me%", Placeholder.empty(), viewer("Alex"))));
        assertEquals("I am Steve", PLAIN.serialize(formatter.format("I am %me%", Placeholder.empty(), viewer("Steve"))));
    }

    @Test
    @DisplayName("Modifiers work on local and global placeholders")
    void modifiers() {
        Placeholder globals = Placeholder.builder()
                .register("online", "3")
                .register("me", FormattingContract::name)
                .build();
        Component result = this.formatter(globals).format(
                "<gold>%coins.comma% %online.plural:player:players% %name.upper% %me.upper%",
                Placeholder.of("coins", "1500", "name", "steve"), viewer("Alex"));
        assertEquals("1,500 players STEVE ALEX", PLAIN.serialize(result));
    }

    @Test
    @DisplayName("Marker characters in global placeholder text are removed")
    void markersFromGlobalsRemoved() {
        Component result = this.formatter(Placeholder.of("nick", "﷐0﷑"))
                .format("%nick% %v%", Placeholder.of("v", "X"), null);
        assertEquals("0 X", PLAIN.serialize(result));
    }

    // --- global text is isolated (changed from MessageFlow 1.1, where it was pasted into the message) ------------

    @Test
    @DisplayName("Colours and unclosed tags in global text end with it")
    void globalTextIsolated() {
        Component result = this.formatter(Placeholder.of("prefix", "&c[Admin] <bold>"))
                .format("%prefix%%player%", Placeholder.of("player", "Steve"), null);
        assertEquals("[Admin] Steve", PLAIN.serialize(result));
        List<ComponentRuns.Run> runs = ComponentRuns.of(result);
        assertEquals(2, runs.size(), runs::toString);
        assertEquals(NamedTextColor.RED, runs.get(0).style().color());
        assertNull(runs.get(1).style().color(), "the name after the prefix is not red");
        assertEquals(TextDecoration.State.FALSE, runs.get(1).style().decoration(TextDecoration.BOLD), "nor bold");
    }

    @Test
    @DisplayName("A <reset> in global text doesn't reset the message around it")
    void globalResetIsolated() {
        Component result = this.formatter(Placeholder.of("prefix", "<reset>x "))
                .format("<green>%prefix%Hi", Placeholder.empty(), null);
        // The global's text is placed inside the green message, so it is green too; its <reset> resets only itself.
        assertEquals(List.of("x Hi"), ComponentRuns.of(result).stream().map(ComponentRuns.Run::content).toList());
        assertEquals(NamedTextColor.GREEN, ComponentRuns.of(result).getFirst().style().color());
    }

    @Test
    @DisplayName("Inside a tag, global text is its plain text")
    void globalInsideTag() {
        Component result = this.formatter(Placeholder.of("server", "&aLobby"))
                .format("<click:run_command:'/server %server%'>go</click>", Placeholder.empty(), null);
        assertEquals(ClickEvent.runCommand("/server Lobby"), click(result));
    }

    // --- <key> placeholders -------------------------------------------------------------------------------------

    @Test
    @DisplayName("<key> is the same placeholder as %key%, and its value stays text")
    void keyTags() {
        Component result = this.formatter().format("Hi <name>! %name% <bold><name></bold>", Placeholder.of("name", "<red>Steve"), null);
        assertEquals("Hi <red>Steve! <red>Steve <red>Steve", PLAIN.serialize(result));
        assertTrue(flatten(result).stream().noneMatch(c -> NamedTextColor.RED.equals(c.color())));
    }

    @Test
    @DisplayName("<key:modifier:argument> applies modifiers")
    void keyTagModifiers() {
        Component result = this.formatter().format("<price:comma> <n:plural:item:items> <name:upper:max:3>",
                Placeholder.builder().register("price", "1500").register("n", "2").register("name", "steve").build(), null);
        assertEquals("1,500 items STE", PLAIN.serialize(result));
    }

    @Test
    @DisplayName("<key> with a global value; a viewer value")
    void keyTagGlobalAndViewer() {
        Component result = this.formatter(Placeholder.of("server", "<gold>Lobby"))
                .format("<who> on <server>", Placeholder.of("who", FormattingContract::name), viewer("Alex"));
        assertEquals("Alex on Lobby", PLAIN.serialize(result));
        assertTrue(flatten(result).stream().anyMatch(c -> NamedTextColor.GOLD.equals(c.color())));
    }

    @Test
    @DisplayName("Standard tags win over a placeholder of the same name, which only %key% reaches")
    void standardTagsWin() {
        Component result = this.formatter().format("<red>a %red%", Placeholder.of("red", "x"), null);
        assertEquals("a x", PLAIN.serialize(result));
        assertEquals(NamedTextColor.RED, ComponentRuns.of(result).getFirst().style().color());
    }

    @Test
    @DisplayName("Without a value, a <key> reads exactly as MiniMessage reads an unknown tag")
    void unknownKeyTags() {
        for (String message : List.of("a <missing> b", "<missing:x:y>c</missing>", "<red>x</missing>y", "I <3 you",
                "<name>a</name>b", "<bold><missing>b</bold>")) {
            Component expected = MiniMessage.miniMessage().deserialize(message);
            assertEquals(ComponentRuns.of(expected), ComponentRuns.of(this.formatter().format(message, Placeholder.empty(), null)), message);
        }
    }

    @Test
    @DisplayName("A value written into a tag can make a standard tag, never a <key> placeholder")
    void valueNeverNamesKeyTag() {
        Placeholder values = Placeholder.builder().register("color", "red").register("ref", "secret").register("secret", "!").build();
        Component result = this.formatter().format("<%color%>a <%ref%>", values, null);
        assertEquals("a <secret>", PLAIN.serialize(result));
        assertEquals(NamedTextColor.RED, ComponentRuns.of(result).getFirst().style().color());
    }

    @Test
    @DisplayName("An escaped \\<key> stays text; a closing </key> stays text, as after a MiniMessage placeholder")
    void escapedAndClosingKeyTags() {
        Placeholder values = Placeholder.of("name", "N");
        assertEquals(ComponentRuns.of(MiniMessage.miniMessage().deserialize("\\<name> N")),
                ComponentRuns.of(this.formatter().format("\\<name> <name>", values, null)));
        assertEquals(ComponentRuns.of(MiniMessage.miniMessage().deserialize("<name>a</name>b",
                        net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.unparsed("name", "N"))),
                ComponentRuns.of(this.formatter().format("<name>a</name>b", values, null)));
    }

    // --- against the reference ----------------------------------------------------------------------------------

    /**
     * Pieces of messages, joined at random: tags, placeholders in text and in tag arguments, legacy codes.
     */
    private static final String[] PIECES = {
            "Hello ", "x", " ", "<red>", "</red>", "<bold>", "</bold>", "<italic>", "<reset>", "<newline>",
            "<gradient:red:blue>", "</gradient>", "<rainbow>", "</rainbow>", "<transition:red:blue:0.5>", "</transition>",
            "<click:run_command:'/tp %a%'>", "</click>", "<hover:show_text:'hi %b%'>", "</hover>",
            "<hover:show_text:'<gold>%a%'>", "%a%", "%a%", "%b%", "%c%", "%missing%", "&c", "&l", "#ff00aa",
            "<color:#00ff00>", "</color>", "<mfph_3>", "<insert:'%b%'>", "</insert>", "\\<red>", "50% ",
            "<a>", "</a>", "<b:upper>", "<c>", "<missing>", "</missing>", "<hover:show_text:'<a>'>", "%a.upper%",
            "%g%", "<g>", "%h.upper%", "<click:run_command:'/s %g%'>", "\\<a>",
    };

    /**
     * Globals for the random messages: one that leaves a colour and a tag open, one plain.
     */
    private static @NotNull Placeholder randomGlobals() {
        return Placeholder.builder()
                .register("g", "<red>G&l")
                .register("h", "plain")
                .build();
    }

    private static @NotNull Placeholder randomValues() {
        return Placeholder.builder()
                .register("a", "Steve")
                .register("b", "<red>inject")
                .register("c", Component.text("C", NamedTextColor.GOLD).decorate(TextDecoration.BOLD))
                .build();
    }

    private static @NotNull List<String> randomMessages(long seed, int count) {
        Random random = new Random(seed);
        List<String> messages = new ArrayList<>(count);
        for (int n = 0; n < count; n++) {
            StringBuilder message = new StringBuilder();
            for (int i = random.nextInt(8); i >= 0; i--) {
                message.append(PIECES[random.nextInt(PIECES.length)]);
            }
            messages.add(message.toString());
        }
        return messages;
    }

    /**
     * Pieces made of the tags an engine may compile without MiniMessage, written every way MiniMessage accepts
     * (aliases, negations, quotes, escapes, upper case), and text that only looks like a tag.
     */
    private static final String[] NATIVE_PIECES = {
            "Hi ", "x", " ", "<red>", "</red>", "<RED>", "<bold>", "</bold>", "<b>", "</b>", "<!italic>", "<italic:false>",
            "<u>", "</u>", "<st>", "<obf>", "<em>", "</em>", "<reset>", "<newline>", "<br>", "<#ff00aa>", "</#ff00aa>",
            "<#FF00AA>", "<color:#00ff00>", "<color:blue>", "<c:gold>", "<colour:red>", "</color>", "</c>", "<grey>",
            "<dark_grey>", "<click:run_command:'/a'>", "<click:suggest_command:/b>", "<click:open_url:'https://x.y/z'>",
            "<click:copy_to_clipboard:c>", "</click>", "<hover:show_text:'<red>hi'>", "<hover:show_text:\"q\">",
            "<hover:show_text:'a<bold>b'>", "</hover>", "<insert:ins>", "</insert>", "%a%", "%b%", "%c%", "<a>", "<c>",
            "<missing>", "</missing>", "</a>", "\\<red>", "\\\\", "\\x", "<", ">", "<>", "</>", "&c", "&l", "&r",
            "#123abc", "<a:upper>", "%g%", "<g>", "<bold:true>", "<click:run_command:'it\\'s'>", "<red:x>", "<my tag>",
            "<!red>", "<newline:x>", "<#12345>", "<color:nope>", "</nope>", "<gradient:red:blue>", "</gradient>",
            "<gradient:#ff0000:gold:0.5>", "<gradient:red:blue:-1>", "<gradient:red>", "<gradient:red:blue:2>", "<rainbow>",
            "<rainbow:!2>", "</rainbow>", "<rainbow:x>", "<click:run_command:'/tp %a%'>", "<insert:%h%>", "<click:suggest_command:/w %g%>",
    };

    /**
     * Values of every length from 0 to 30 characters, some with code points outside the BMP.
     */
    private static @NotNull List<String> valuesOfEveryLength() {
        List<String> values = new ArrayList<>();
        StringBuilder value = new StringBuilder();
        for (int length = 0; length <= 30; length++) {
            values.add(value.toString());
            values.add(value.toString().replace('a', 'é') + (length % 7 == 3 ? "😀" : ""));
            value.append((char) ('a' + length % 26));
        }
        return values;
    }

    @Test
    @DisplayName("Gradients and rainbows over values of every length read as MiniMessage reads them")
    void colorChangesOverValues() {
        TestFormatter reference = new ReferenceFormatter(Placeholder.empty());
        TestFormatter formatter = this.formatter();
        List<String> messages = List.of(
                "<gradient:red:blue>%v%</gradient>",
                "<gradient:red:blue>Welcome %v%!</gradient> after",
                "<gradient:#ff0000:#00ff00:#0000ff:0.4>[%v%] <bold>%v%</bold></gradient>",
                "<gradient:gold:aqua:-0.7>%v%<newline>%v%</gradient>",
                "<gradient>%v%</gradient>",
                "<red>a <gradient:green:blue:1>%v%</gradient> b",
                "<rainbow>%v%</rainbow>",
                "<rainbow:!>x%v%y</rainbow>",
                "<rainbow:3><u>%v%</u></rainbow>",
                "<rainbow:!7><click:run_command:'/a'>%v%</click> z</rainbow>",
                "<gradient:red:blue><v></gradient> <rainbow><v:upper></rainbow>");
        for (String message : messages) {
            for (String value : valuesOfEveryLength()) {
                Placeholder values = Placeholder.of("v", value);
                assertEquals(ComponentRuns.of(reference.format(message, values, null)),
                        ComponentRuns.of(formatter.format(message, values, null)), message + " with '" + value + "'");
            }
        }
    }

    @Test
    @DisplayName("A gradient over a value with its own style reads as MiniMessage reads it")
    void colorChangeOverStyledValue() {
        TestFormatter reference = new ReferenceFormatter(Placeholder.empty());
        TestFormatter formatter = this.formatter();
        for (Component value : List.of(Component.text("Gold", NamedTextColor.GOLD), Component.text("Bold").decorate(TextDecoration.BOLD),
                Component.text("a").append(Component.text("b", NamedTextColor.RED)), Component.translatable("item.x"))) {
            Placeholder values = Placeholder.of("v", value);
            String message = "<gradient:red:blue>x %v% y</gradient>";
            assertEquals(ComponentRuns.of(reference.format(message, values, null)),
                    ComponentRuns.of(formatter.format(message, values, null)), value.toString());
        }
    }

    @Test
    @DisplayName("Click and insert arguments with values that change read as before")
    void eventArgumentsWithValues() {
        TestFormatter reference = new ReferenceFormatter(Placeholder.of("server", "&aLobby"));
        TestFormatter formatter = this.formatter(Placeholder.of("server", "&aLobby"));
        List<String> messages = List.of(
                "<click:run_command:'/tp %v%'>[tp %v%]</click>",
                "<click:suggest_command:/msg %v% >x</click>",
                "<click:run_command:\"/pay %v% 10\"><green>%v%</green></click>",
                "<insert:'%v%'>%v%</insert> <click:copy_to_clipboard:'%v% %server%'>c</click>",
                "<click:open_url:'https://x.y/%v%'><u>link</u></click>",
                "<click:run_command:'/w %v% %missing%'>w</click>",
                "<hover:show_text:'hi %v%'>h</hover> <click:run_command:'/a %v%'>a</click>",
                "<gradient:red:blue><click:run_command:'/a %v%'>%v%</click></gradient>");
        List<String> values = List.of("Steve", "", "Steve'><red>x", "#ff00aa", "a#ff00aab", ":colon:", "%server%", "&c§l",
                "\\back", "\"q\"", "é😀", "a b c");
        for (String message : messages) {
            for (String value : values) {
                Placeholder placeholders = Placeholder.of("v", value);
                assertEquals(ComponentRuns.of(reference.format(message, placeholders, null)),
                        ComponentRuns.of(formatter.format(message, placeholders, null)), message + " with '" + value + "'");
            }
        }
    }

    @Test
    @DisplayName("Same result as the reference formatter on messages of native tags")
    void sameAsReferenceNativeTags() {
        Placeholder values = randomValues();
        TestFormatter reference = new ReferenceFormatter(randomGlobals());
        TestFormatter formatter = this.formatter(randomGlobals());
        Random random = new Random(3);
        for (int n = 0; n < 20_000; n++) {
            StringBuilder builder = new StringBuilder();
            for (int i = random.nextInt(10); i >= 0; i--) {
                builder.append(NATIVE_PIECES[random.nextInt(NATIVE_PIECES.length)]);
            }
            String message = builder.toString();
            List<ComponentRuns.Run> expected = ComponentRuns.of(reference.format(message, values, null));
            assertEquals(expected, ComponentRuns.of(formatter.format(message, values, null)), message);
        }
    }

    @Test
    @DisplayName("Same result as the reference formatter, first and second time")
    void sameAsReference() {
        Placeholder values = randomValues();
        TestFormatter reference = new ReferenceFormatter(randomGlobals());
        TestFormatter formatter = this.formatter(randomGlobals());
        for (String message : randomMessages(1, 10_000)) {
            List<ComponentRuns.Run> expected = ComponentRuns.of(reference.format(message, values, null));
            assertEquals(expected, ComponentRuns.of(formatter.format(message, values, null)), message);
            assertEquals(expected, ComponentRuns.of(formatter.format(message, values, null)), "again: " + message);
        }
    }

    @Test
    @DisplayName("Formatting from many threads gives the same results as from one")
    void threads() throws Exception {
        Placeholder values = randomValues();
        TestFormatter formatter = this.formatter();
        List<String> messages = randomMessages(2, 200);
        List<List<ComponentRuns.Run>> expected = new ArrayList<>();
        for (String message : messages) {
            expected.add(ComponentRuns.of(formatter.format(message, values, null)));
        }
        AtomicInteger mismatches = new AtomicInteger();
        try (ExecutorService pool = Executors.newFixedThreadPool(8)) {
            List<Future<?>> tasks = new ArrayList<>();
            for (int t = 0; t < 8; t++) {
                int offset = t;
                tasks.add(pool.submit(() -> {
                    for (int n = 0; n < 2_000; n++) {
                        int i = (n * 7 + offset) % messages.size();
                        if (!expected.get(i).equals(ComponentRuns.of(formatter.format(messages.get(i), values, null)))) {
                            mismatches.incrementAndGet();
                        }
                    }
                }));
            }
            for (Future<?> task : tasks) {
                task.get();
            }
        }
        assertEquals(0, mismatches.get());
    }
}
