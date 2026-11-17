package fr.robie.minimessageflow.core;

import fr.robie.minimessageflow.MiniMessageFlow;
import fr.robie.minimessageflow.Placeholder;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The native compiler, unchecked, gives the very tree MiniMessage gives (equals, not only the same on screen).
 */
class StrictTreeTest {

    private static final MiniMessage MINI = MiniMessage.miniMessage();
    private final MiniMessageFlow flow = MiniMessageFlow.builder().verifyNative(false).build();

    @ParameterizedTest
    @ValueSource(strings = {
            "<hover:show_text:hi>x",
            "<hover:show_text:'<red>hi'>x",
            "<hover:show_text:'a <b>b</b> c'>x</hover> y",
    })
    @DisplayName("The text of a hover is compacted as MiniMessage compacts it")
    void hoverText(String message) {
        assertEquals(MINI.deserialize(message).compact(), this.flow.format(message).compact(), message);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Steve", "a", "a long value"})
    @DisplayName("The text of a hover with a value is compacted too")
    void hoverTextWithValue(String value) {
        String message = "<hover:show_text:'hi %v%'>x";
        Component expected = MINI.deserialize("<hover:show_text:'hi " + value + "'>x").compact();
        assertEquals(expected, this.flow.format(message, Placeholder.of("v", value)).compact(), value);
    }
}
