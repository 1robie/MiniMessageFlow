package fr.robie.minimessageflow;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class RawModifierTest {

    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    private static MiniMessageFlow withResolver(String key, String text) {
        return MiniMessageFlow.builder().resolver(Resolver.of(Placeholder.of(key, text))).build();
    }

    @ParameterizedTest
    @ValueSource(strings = {"%nick.raw%", "<nick:raw>"})
    @DisplayName("A resolver's text with raw shows its codes and tags as written")
    void resolverText(String message) {
        Component rendered = withResolver("nick", "&c<b>Steve").compile(message).render();
        assertEquals("&c<b>Steve", PLAIN.serialize(rendered));
        assertNull(colourOfText(rendered, "&c<b>Steve"));
    }

    @Test
    @DisplayName("Without raw, the same resolver text is formatted")
    void resolverTextFormatted() {
        Component rendered = withResolver("nick", "&cSteve").compile("%nick%").render();
        assertEquals("Steve", PLAIN.serialize(rendered));
        assertEquals(NamedTextColor.RED, colourOfText(rendered, "Steve"));
    }

    @Test
    @DisplayName("Modifiers before raw apply, then the result is plain")
    void modifiersBefore() {
        MiniMessageFlow flow = withResolver("nick", "&cSteveTheGreat");
        assertEquals("&cSteve", PLAIN.serialize(flow.compile("%nick.max:7.raw%").render()));
        assertEquals("&CSTEVE", PLAIN.serialize(flow.compile("%nick.max:7.upper.raw%").render()));
    }

    @Test
    @DisplayName("A component value with raw gives its plain text, unstyled")
    void componentValue() {
        Component value = Component.text("Sword", NamedTextColor.GOLD);
        Component rendered = MiniMessageFlow.builder().build().compile("[%item.raw%]").render(Placeholder.of("item", value));
        assertEquals("[Sword]", PLAIN.serialize(rendered));
        assertNull(colourOfText(rendered, "Sword"));
    }

    @Test
    @DisplayName("A call's text is plain already: raw changes nothing")
    void callValue() {
        MiniMessageFlow flow = MiniMessageFlow.builder().build();
        Placeholder values = Placeholder.of("v", "&c<red>x");
        assertEquals(PLAIN.serialize(flow.compile("%v%").render(values)), PLAIN.serialize(flow.compile("%v.raw%").render(values)));
    }

    @Test
    @DisplayName("A value with raw in a click argument stays one argument")
    void clickArgument() {
        MiniMessageFlow flow = withResolver("v", "x'><red>y");
        Component rendered = flow.compile("<click:run_command:'/say %v.raw%'>go").render();
        assertEquals(ClickEvent.runCommand("/say xredy"), findClick(rendered));
        assertEquals("go", PLAIN.serialize(rendered));
    }

    @Test
    @DisplayName("A value tag with raw: <papi:x:raw> and %papi:x.raw%")
    void valueTag() {
        MiniMessageFlow flow = MiniMessageFlow.builder().valueTag("papi", (arguments, viewer) -> "&a" + arguments.get(0)).build();
        assertEquals("&anick", PLAIN.serialize(flow.compile("<papi:nick:raw>").render()));
        assertEquals("&anick", PLAIN.serialize(flow.compile("%papi:nick.raw%").render()));
        assertEquals("nick", PLAIN.serialize(flow.compile("<papi:nick>").render()));
    }

    @Test
    @DisplayName("A constant with raw is its plain text")
    void constant() {
        MiniMessageFlow flow = MiniMessageFlow.builder().constant("prefix", "<gold>[Shop]").build();
        Component rendered = flow.compile("%prefix.raw%").render();
        assertEquals("[Shop]", PLAIN.serialize(rendered));
        assertNull(colourOfText(rendered, "[Shop]"));
    }

    @Test
    @DisplayName("Without a value, a raw placeholder stays as written")
    void unknown() {
        assertEquals("%nobody.raw%", PLAIN.serialize(MiniMessageFlow.builder().build().compile("%nobody.raw%").render()));
    }

    @Test
    @DisplayName("Placeholder.parse reads raw as no change")
    void parse() {
        assertEquals("STEVE", Placeholder.of("nick", "Steve").parse("%nick.upper.raw%"));
    }

    @Test
    @DisplayName("raw is reserved")
    void reserved() {
        assertThrows(IllegalArgumentException.class, () -> PlaceholderModifiers.register("raw", (v, a) -> v));
        assertThrows(IllegalArgumentException.class, () -> PlaceholderModifiers.register("RAW", (v, a) -> v));
        assertThrows(IllegalArgumentException.class, () -> MiniMessageFlow.builder().modifier("raw", (v, a) -> v));
    }

    private static NamedTextColor colourOfText(Component component, String text) {
        return inherited(component, text, null);
    }

    private static NamedTextColor inherited(Component node, String text, net.kyori.adventure.text.format.TextColor parent) {
        net.kyori.adventure.text.format.TextColor colour = node.color() != null ? node.color() : parent;
        if (node instanceof net.kyori.adventure.text.TextComponent t && t.content().contains(text)) {
            return colour == null ? null : NamedTextColor.namedColor(colour.value());
        }
        for (Component child : node.children()) {
            NamedTextColor found = inherited(child, text, colour);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static Object findClick(Component component) {
        if (component.clickEvent() != null) {
            return component.clickEvent();
        }
        for (Component child : component.children()) {
            Object found = findClick(child);
            if (found != null) {
                return found;
            }
        }
        return null;
    }
}
