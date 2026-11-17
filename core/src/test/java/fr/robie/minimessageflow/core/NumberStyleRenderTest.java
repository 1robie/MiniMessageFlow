package fr.robie.minimessageflow.core;

import fr.robie.minimessageflow.MiniMessageFlow;
import fr.robie.minimessageflow.NumberStyle;
import fr.robie.minimessageflow.Placeholder;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NumberStyleRenderTest {

    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @DisplayName("The builder's number style reaches a render, in %key% and <key> placeholders")
    void builderStyle(boolean nativeCompiler) {
        MiniMessageFlow european = MiniMessageFlow.builder().nativeCompiler(nativeCompiler).numberStyle(NumberStyle.EUROPEAN).build();
        MiniMessageFlow auto = MiniMessageFlow.builder().nativeCompiler(nativeCompiler).build();
        Placeholder values = Placeholder.of("price", "1234,5", "count", "1,234");
        String message = "<gold>%price.comma%</gold> <price:2f> %count.add:1%";
        assertEquals("1.234,50 1234,50 2,234", PLAIN.serialize(european.format(message, values)));
        assertEquals("1234.50 1234.50 1235", PLAIN.serialize(auto.format("%price.2f% <price:2f> %count.add:1%", values)));
    }
}
