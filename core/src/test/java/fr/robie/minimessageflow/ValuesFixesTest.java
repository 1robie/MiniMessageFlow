package fr.robie.minimessageflow;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class ValuesFixesTest {

    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    @ParameterizedTest
    @ValueSource(strings = {"my key", "a\tb", "50%", "<x>", "a&b", "a§b", "a\\b", "it's", "a\"b", "player#id"})
    @DisplayName("A key no placeholder can name is refused when it is declared")
    void badNames(String name) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> ValueKeys.builder().key(name));
        assertTrue(e.getMessage().contains(name), e.getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {"player", "player.name", "arg:0", "vault_eco_balance", "élève", "a-b"})
    @DisplayName("Names a placeholder can have are declared")
    void goodNames(String name) {
        assertEquals(name, ValueKeys.builder().key(name).name());
    }

    @Test
    @DisplayName("A double is written in full in the instance's number style, which its modifiers read")
    void doubles() {
        MiniMessageFlow.Builder builder = MiniMessageFlow.builder();
        ValueKey price = builder.key("price");
        MiniMessageFlow flow = builder.build();
        Template template = flow.compile("%price% %price.2f% %price.comma%");
        Values values = flow.newValues();
        assertEquals("2.5 2.50 2.50", PLAIN.serialize(template.render(values.set(price, 2.5), null)));
        assertEquals("1234.5 1234.50 1,234.50", PLAIN.serialize(template.render(values.set(price, 1234.5), null)));
        assertEquals("1000 1000.00 1,000", PLAIN.serialize(template.render(values.set(price, 1000.0), null)));
        assertEquals("0.000001", PLAIN.serialize(flow.compile("%price%").render(values.set(price, 1e-6), null)));
        assertEquals("0", PLAIN.serialize(flow.compile("%price%").render(values.set(price, -0.0), null)));
        assertThrows(IllegalArgumentException.class, () -> values.set(price, Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> values.set(price, Double.POSITIVE_INFINITY));

        MiniMessageFlow.Builder europeanBuilder = MiniMessageFlow.builder().numberStyle(NumberStyle.EUROPEAN);
        ValueKey euroPrice = europeanBuilder.key("price");
        MiniMessageFlow european = europeanBuilder.build();
        assertEquals("1234,5 1234,50 1.234,50", PLAIN.serialize(european.compile("%price% %price.2f% %price.comma%")
                .render(european.newValues().set(euroPrice, 1234.5), null)));
    }

    @Test
    @DisplayName("set with a typed null: empty text or the empty component; unset removes the value")
    void nulls() {
        MiniMessageFlow.Builder builder = MiniMessageFlow.builder();
        ValueKey name = builder.key("name");
        MiniMessageFlow flow = builder.build();
        Values values = flow.newValues();
        Template template = flow.compile("[%name%]");
        assertEquals("[]", PLAIN.serialize(template.render(values.set(name, (String) null), null)));
        assertEquals("[]", PLAIN.serialize(template.render(values.set(name, (Component) null), null)));
        assertEquals("[%name%]", PLAIN.serialize(template.render(values.unset(name), null)));
    }

    @Test
    @DisplayName("toString shows the keys set and their kinds, never the values")
    void toStringHidesValues() {
        MiniMessageFlow.Builder builder = MiniMessageFlow.builder();
        ValueKey player = builder.key("player");
        ValueKey item = builder.key("item");
        ValueKey time = builder.key("time");
        ValueKey unused = builder.key("unused");
        Values values = builder.build().newValues()
                .set(player, "SecretSteve")
                .set(item, Component.text("SecretSword"))
                .setComputed(time, () -> "x");
        String text = values.toString();
        assertEquals("Values{player=text, item=component, time=dynamic}", text);
        assertFalse(text.contains("Secret"));
        assertFalse(values.isSet(unused));
    }
}
