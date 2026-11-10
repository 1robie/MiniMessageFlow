package fr.robie.minimessageflow;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for Placeholder class including static, dynamic, and player-specific placeholders.
 */
@DisplayName("Placeholder Tests")
class PlaceholderTest {

    @Test
    @DisplayName("Static placeholder parsing works correctly")
    void testStaticPlaceholderParsing() {
        Placeholder p = Placeholder.of("name", "John");
        String result = p.parse("Hello %name%!");
        assertEquals("Hello John!", result);
    }

    @Test
    void placeholder_shouldNotMatchStringFormatSpecifiers() {
        Placeholder p = Placeholder.of("xxx", "yyy");
        String result = p.parse("Hello %02d %xxx%!");
        assertEquals("Hello %02d yyy!", result);
    }

    @Test
    @DisplayName("Multiple static placeholders parse correctly")
    void testMultiplePlaceholders() {
        Placeholder p = Placeholder.of("name", "John", "level", "10");
        String result = p.parse("Player %name% is level %level%");
        assertEquals("Player John is level 10", result);
    }

    @Test
    @DisplayName("Builder supports static placeholder values")
    void testBuilderWithStaticValues() {
        Placeholder p = Placeholder.builder()
                .register("name", "Alice")
                .register("status", "online")
                .build();
        String result = p.parse("%name% is %status%");
        assertEquals("Alice is online", result);
    }

    @Test
    @DisplayName("Null value converts to empty string")
    void testNullValueBecomesEmpty() {
        Placeholder p = Placeholder.of("key", (String) null);
        String result = p.parse("Value: %key%");
        assertEquals("Value: ", result);
    }

    @Test
    @DisplayName("Parse returns null for null input")
    void testParseNullMessage() {
        Placeholder p = Placeholder.of("key", "value");
        assertNull(p.parse(null));
    }

    @Test
    @DisplayName("Empty placeholder returns message unchanged")
    void testEmptyPlaceholder() {
        Placeholder p = Placeholder.empty();
        String message = "Hello world";
        assertEquals(message, p.parse(message));
    }

    @Test
    @DisplayName("Unregistered placeholder remains unchanged")
    void testUnregisteredPlaceholder() {
        Placeholder p = Placeholder.of("registered", "value");
        String result = p.parse("Known: %registered%, Unknown: %unknown%");
        assertEquals("Known: value, Unknown: %unknown%", result);
    }

    @Test
    @DisplayName("Builder supports dynamic placeholders via Supplier")
    void testBuilderWithSupplier() {
        Placeholder p = Placeholder.builder()
                .register("count", () -> "42")
                .build();
        String result = p.parse("Count: %count%");
        assertEquals("Count: 42", result);
    }

    @Test
    @DisplayName("Supplier returning null becomes empty string")
    void testSupplierReturningNull() {
        Placeholder p = Placeholder.builder()
                .register("value", () -> null)
                .build();
        String result = p.parse("Value: %value%");
        assertEquals("Value: ", result);
    }

    @Test
    @DisplayName("Mixed static and dynamic placeholders work together")
    void testMixedStaticAndDynamic() {
        Placeholder p = Placeholder.builder()
                .register("name", "John")
                .register("time", () -> "12:00")
                .build();
        String result = p.parse("%name% logged in at %time%");
        assertEquals("John logged in at 12:00", result);
    }

    @Test
    @DisplayName("Placeholder.isEmpty() works correctly")
    void testIsEmpty() {
        assertTrue(Placeholder.empty().isEmpty());
        assertFalse(Placeholder.of("key", "value").isEmpty());
    }

    @Test
    @DisplayName("getMap() returns immutable view")
    void testGetMapIsImmutable() {
        Placeholder p = Placeholder.of("key", "value");
        assertThrows(UnsupportedOperationException.class, () -> {
            p.getMap().put("new", PlaceholderValue.ofStatic("entry"));
        });
    }

    @Test
    @DisplayName("Multiple parse calls produce consistent results")
    void testMultipleParseCalls() {
        Placeholder p = Placeholder.builder()
                .register("value", () -> "same")
                .build();
        String result1 = p.parse("Test %value%");
        String result2 = p.parse("Test %value%");
        assertEquals(result1, result2);
    }

    @Test
    @DisplayName("Special regex characters in values are properly escaped")
    void testSpecialCharactersInValues() {
        Placeholder p = Placeholder.of("regex", "$1.50");
        String result = p.parse("Price: %regex%");
        assertEquals("Price: $1.50", result);
    }

    @Test
    @DisplayName("Supplier exception results in unchanged placeholder")
    void testSupplierException() {
        Placeholder p = Placeholder.builder()
                .register("broken", () -> {
                    throw new RuntimeException("Intentional error");
                })
                .build();
        String result = p.parse("Value: %broken%");
        // Exception is caught, placeholder left unchanged
        assertEquals("Value: %broken%", result);
    }

    @Test
    @DisplayName("Only the values used by the message are evaluated, each once")
    void testLazyEvaluation() {
        int[] used = {0};
        int[] unused = {0};
        Placeholder p = Placeholder.builder()
                .register("used", () -> String.valueOf(++used[0]))
                .register("unused", () -> String.valueOf(++unused[0]))
                .build();
        assertEquals("1 and 1", p.parse("%used% and %used%"));
        assertEquals(1, used[0]);
        assertEquals(0, unused[0]);
    }

    @Test
    @DisplayName("A percent sign before a placeholder does not hide it")
    void testPercentBeforePlaceholder() {
        Placeholder p = Placeholder.of("player", "Steve");
        assertEquals("100% sure Steve", p.parse("100% sure %player%"));
        assertEquals("50%off%Steve", p.parse("50%off%%player%"));
        assertEquals("%%Steve", p.parse("%%%player%"));
    }

    @Test
    @DisplayName("A text without a known placeholder is returned as is")
    void testUnchangedTextIsSameInstance() {
        Placeholder p = Placeholder.of("player", "Steve");
        String text = "50% off %unknown%";
        assertSame(text, p.parse(text));
    }

    @Test
    @DisplayName("Player-specific placeholders make a text player dependent")
    void testPlayerDependent() {
        Placeholder p = Placeholder.builder()
                .register("name", "John")
                .register("health", player -> "20")
                .build();
        assertTrue(p.isViewerDependent("hp: %health%"));
        assertFalse(p.isViewerDependent("name: %name%"));
        assertFalse(Placeholder.of("name", "John").isViewerDependent("%name%"));
    }
}
