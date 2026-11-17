package fr.robie.minimessageflow.core;

import fr.robie.minimessageflow.MiniMessageFlow;
import fr.robie.minimessageflow.Placeholder;
import fr.robie.minimessageflow.Template;
import fr.robie.minimessageflow.ValueTag;
import net.kyori.adventure.identity.Identity;
import net.kyori.adventure.pointer.Pointered;
import net.kyori.adventure.pointer.Pointers;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class TagsTest {

    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    private static Pointered viewer(String name) {
        Pointers pointers = Pointers.builder().withStatic(Identity.NAME, name).build();
        return new Pointered() {
            @Override
            public @NotNull Pointers pointers() {
                return pointers;
            }
        };
    }

    /**
     * {@code <papi:a:b>}: the arguments joined, with the viewer's name.
     */
    private static ValueTag papi(AtomicInteger calls) {
        return (arguments, viewer) -> {
            calls.incrementAndGet();
            String who = viewer == null ? "nobody" : viewer.get(Identity.NAME).orElse("?");
            return arguments.isEmpty() ? null : String.join(":", arguments) + "@" + who;
        };
    }

    @Test
    @DisplayName("tags(...) called twice keeps both; the last one added wins a name both claim")
    void tagsAddUp() {
        MiniMessageFlow flow = MiniMessageFlow.builder()
                .tags(TagResolver.resolver("one", Tag.selfClosingInserting(Component.text("1"))))
                .tags(TagResolver.resolver("two", Tag.selfClosingInserting(Component.text("2"))))
                .tag("three", Tag.selfClosingInserting(Component.text("3")))
                .tags(TagResolver.resolver("one", Tag.selfClosingInserting(Component.text("other"))))
                .build();
        assertEquals("other 2 3", PLAIN.serialize(flow.format("<one> <two> <three>")));
    }

    @Test
    @DisplayName("A value tag reads its arguments and the viewer, in <tag:args> and %tag:args%")
    void valueTagForms() {
        AtomicInteger calls = new AtomicInteger();
        MiniMessageFlow flow = MiniMessageFlow.builder().valueTag("papi", papi(calls)).build();
        assertEquals("player_name@Steve player_name@Steve a:b@Steve",
                PLAIN.serialize(flow.format("<papi:player_name> %papi:player_name% <papi:'a':b>", Placeholder.empty(), viewer("Steve"))));
        assertEquals("player_name@nobody", PLAIN.serialize(flow.format("<papi:player_name>")));
        assertEquals("<papi>", PLAIN.serialize(flow.format("<papi>")), "no value without arguments: shown as written");
    }

    @Test
    @DisplayName("A value tag is asked once per render, keeps the fast path, and changes with the viewer")
    void valueTagPerRender() {
        AtomicInteger calls = new AtomicInteger();
        MiniMessageFlow flow = MiniMessageFlow.builder().valueTag("papi", papi(calls)).build();
        Template template = flow.compile("<green>Hi <papi:player_name></green>!");
        assertEquals(Template.Kind.NATIVE, template.kind());
        int afterCompile = calls.get();
        assertEquals(0, afterCompile, "never asked while compiling");
        assertEquals("Hi player_name@Alex!", PLAIN.serialize(template.render(Placeholder.empty(), viewer("Alex"))));
        assertEquals("Hi player_name@Steve!", PLAIN.serialize(template.render(Placeholder.empty(), viewer("Steve"))));
        assertEquals(2, calls.get());
    }

    @Test
    @DisplayName("Modifiers apply to a value tag; an argument with a dot that is no modifier stays an argument")
    void valueTagModifiers() {
        ValueTag balance = (arguments, viewer) -> arguments.equals(List.of("vault_eco_balance")) ? "1234567"
                : arguments.equals(List.of("server_1.5")) ? "ok" : null;
        MiniMessageFlow flow = MiniMessageFlow.builder().valueTag("papi", balance).build();
        assertEquals("1,234,567 1,234,567 ok", PLAIN.serialize(
                flow.format("<papi:vault_eco_balance:comma> %papi:vault_eco_balance.comma% <papi:server_1.5>")));
    }

    @Test
    @DisplayName("A value tag's text is formatted on its own; its component keeps its style; in a click, its plain text")
    void valueTagValues() {
        ValueTag colored = new ValueTag() {
            @Override
            public @Nullable String resolve(@NotNull List<String> arguments, @Nullable Pointered viewer) {
                return "&cred";
            }

            @Override
            public @Nullable Component resolveComponent(@NotNull List<String> arguments, @Nullable Pointered viewer) {
                return arguments.isEmpty() ? null : Component.text("comp", NamedTextColor.GOLD);
            }
        };
        MiniMessageFlow flow = MiniMessageFlow.builder().valueTag("v", colored).build();
        Component text = flow.format("<v> after").compact();
        assertEquals("red after", PLAIN.serialize(text));
        assertEquals(NamedTextColor.GOLD, flow.format("<v:x>").compact().color());
        assertEquals(ClickEvent.runCommand("/say red"),
                flow.format("<click:run_command:'/say %v%'>go").compact().clickEvent());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "Papi", "a b", "a:b", "a.b", "red", "click"})
    @DisplayName("A value tag's name is a lower-case tag name that no standard tag claims")
    void valueTagNames(String name) {
        assertThrows(IllegalArgumentException.class, () -> MiniMessageFlow.builder().valueTag(name, (a, v) -> "x"));
    }

    @Test
    @DisplayName("A value tag a tag of tags(...) would claim is refused when the instance is built")
    void valueTagClaimedByTags() {
        MiniMessageFlow.Builder builder = MiniMessageFlow.builder()
                .tag("papi", Tag.selfClosingInserting(Component.text("x")))
                .valueTag("papi", (a, v) -> "y");
        assertThrows(IllegalArgumentException.class, builder::build);
    }

    @Test
    @DisplayName("Value tags and resolvers are asked in the order they were added")
    void order() {
        MiniMessageFlow flow = MiniMessageFlow.builder()
                .resolver((key, viewer) -> key.equals("v:x") ? "resolver" : null)
                .valueTag("v", (a, viewer) -> "tag")
                .build();
        assertEquals("resolver tag", PLAIN.serialize(flow.format("<v:x> <v:y>")));
    }
}
