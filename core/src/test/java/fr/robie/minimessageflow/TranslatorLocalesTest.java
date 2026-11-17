package fr.robie.minimessageflow;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TranslatorLocalesTest {

    @Test
    @DisplayName("Many locales asked for do not make the fallback cache grow without end")
    void boundedLocales() {
        MiniMessageFlowTranslator translator = MiniMessageFlowTranslator.create(Key.key("test", "locales"), MiniMessageFlow.create());
        translator.register("hi", Locale.ENGLISH, "Hello");
        for (int i = 0; i < 10_000; i++) {
            Locale locale = Locale.of("en", "", "v" + i);
            Component translated = translator.translate(Component.translatable("hi"), locale);
            assertEquals(Component.text("Hello"), translated);
        }
        assertTrue(MiniMessageFlowTranslator.cachedLocales() <= 256, "cached: " + MiniMessageFlowTranslator.cachedLocales());
    }
}
