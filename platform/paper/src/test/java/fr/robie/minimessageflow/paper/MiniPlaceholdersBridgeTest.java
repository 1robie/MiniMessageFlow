package fr.robie.minimessageflow.paper;

import fr.robie.minimessageflow.MiniMessageFlow;
import fr.robie.minimessageflow.Placeholder;
import fr.robie.minimessageflow.Template;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.identity.Identity;
import net.kyori.adventure.pointer.Pointered;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

class MiniPlaceholdersBridgeTest {

    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();
    private final Player steve = Fakes.player("Steve", UUID.randomUUID());

    /**
     * {@code <player_name>} (the parse's target, as 3.x reads it), {@code <server_echo:a:b>}, {@code <rank>} in gold.
     */
    static TagResolver targetPlaceholders() {
        return TagResolver.resolver(
                TagResolver.resolver("player_name", (arguments, context) -> Tag.selfClosingInserting(Component.text(
                        context.target() == null ? "nobody" : context.target().get(Identity.NAME).orElse("?")))),
                TagResolver.resolver("server_echo", (arguments, context) -> {
                    StringBuilder text = new StringBuilder();
                    while (arguments.hasNext()) {
                        text.append('[').append(arguments.pop().value()).append(']');
                    }
                    return Tag.selfClosingInserting(Component.text(text.toString()));
                }),
                TagResolver.resolver("rank", Tag.selfClosingInserting(Component.text("Admin", NamedTextColor.GOLD))));
    }

    /**
     * MiniPlaceholders 3.x's shape: {@code audienceGlobalPlaceholders()}, the audience being the parse's target.
     */
    public static final class Api3 {
        public static TagResolver audienceGlobalPlaceholders() {
            return targetPlaceholders();
        }
    }

    /**
     * MiniPlaceholders 2.x's shape: a resolver for an audience, or the global one.
     */
    public static final class Api2 {
        public static TagResolver getAudienceGlobalPlaceholders(Audience audience) {
            String name = audience.get(Identity.NAME).orElse("?");
            return TagResolver.resolver("player_name", Tag.selfClosingInserting(Component.text(name + " (2.x)")));
        }

        public static TagResolver getGlobalPlaceholders() {
            return TagResolver.resolver("player_name", Tag.selfClosingInserting(Component.text("global (2.x)")));
        }
    }

    private static MiniMessageFlow flow(Function<Pointered, TagResolver> source) {
        return MiniMessageFlow.builder().resolver(new MiniPlaceholdersBridge.Placeholders(source)).build();
    }

    private String plain(MiniMessageFlow flow, String message, Pointered viewer) {
        return PLAIN.serialize(flow.format(message, Placeholder.empty(), viewer));
    }

    @Test
    @DisplayName("3.x: its tags as written and as %key%, arguments, modifiers, the viewer as the audience")
    void three() {
        MiniMessageFlow flow = flow(MiniPlaceholdersBridge.source(Api3.class.getName()));
        assertEquals("Steve Steve [a][b c] STEVE STEVE",
                this.plain(flow, "<player_name> %player_name% <server_echo:a:'b c'> <player_name:upper> %player_name.upper%", this.steve));
        assertEquals("nobody", this.plain(flow, "<player_name>", null));
        assertEquals("<unknown_tag>", this.plain(flow, "<unknown_tag>", this.steve), "not its tag: shown as written");
    }

    @Test
    @DisplayName("On Adventure 4 (Paper 1.20.6, 1.21) the tag is asked directly, with the viewer; on 5 (Paper 26) it is parsed")
    void directOnAdventure4() {
        Component direct = fr.robie.minimessageflow.core.AdventureBridges.current()
                .insertedTag(targetPlaceholders(), "server_echo", java.util.List.of("a", "b c"), this.steve);
        Component target = fr.robie.minimessageflow.core.AdventureBridges.current()
                .insertedTag(targetPlaceholders(), "player_name", java.util.List.of(), this.steve);
        boolean adventure4 = fr.robie.minimessageflow.core.AdventureBridges.current().major() == 4;
        String paper = System.getProperty("minimessageflow.test.paperVersion", "1.20.6");
        assertEquals(!paper.startsWith("26."), adventure4, "Paper " + paper);
        if (adventure4) {
            assertEquals(Component.text("[a][b c]"), direct);
            assertEquals(Component.text("Steve"), target);
        } else {
            assertNull(direct);
            assertNull(target);
        }
    }

    @Test
    @DisplayName("2.x: the audience's resolver, the global one without an audience")
    void two() {
        MiniMessageFlow flow = flow(MiniPlaceholdersBridge.source(Api2.class.getName()));
        assertEquals("Steve (2.x)", this.plain(flow, "<player_name>", this.steve));
        assertEquals("global (2.x)", this.plain(flow, "<player_name>", null));
    }

    @Test
    @DisplayName("A component keeps its style, and a message keeps the fast path")
    void componentsAndFastPath() {
        MiniMessageFlow flow = flow(MiniPlaceholdersBridge.source(Api3.class.getName()));
        Component rendered = flow.format("<rank> after", Placeholder.empty(), this.steve).compact();
        assertEquals(NamedTextColor.GOLD, rendered.children().isEmpty() ? rendered.color() : rendered.children().getFirst().color());
        Template template = flow.compile("<green>Hi <player_name>");
        assertEquals(Template.Kind.NATIVE, template.kind());
    }

    @Test
    @DisplayName("The real MiniPlaceholders 3.1.0 API is found; a class that is not there gives none")
    void realApi() {
        assertNotNull(MiniPlaceholdersBridge.source("io.github.miniplaceholders.api.MiniPlaceholders"));
        assertNull(MiniPlaceholdersBridge.source("io.github.miniplaceholders.api.Missing"));
        assertNull(MiniPlaceholdersBridge.source(String.class.getName()), "a class without the API's methods");
    }
}
