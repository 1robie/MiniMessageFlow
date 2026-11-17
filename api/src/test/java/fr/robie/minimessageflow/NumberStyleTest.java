package fr.robie.minimessageflow;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

class NumberStyleTest {

    private static String apply(NumberStyle style, String value, String chain) {
        return ModifierSet.builder().numberStyle(style).build().apply(value, chain);
    }

    @ParameterizedTest(name = "{0} reads ''{1}'' as {2}")
    @CsvSource(delimiter = '|', nullValues = "none", value = {
            "AUTO     | 1250         | 1250",
            "AUTO     | 1,234        | 1234",
            "AUTO     | 1,5          | 1.5",
            "AUTO     | 0,125        | 0.125",
            "AUTO     | 0.125        | 0.125",
            "AUTO     | 1.234        | 1.234",
            "AUTO     | 1.234,5      | 1234.5",
            "AUTO     | 1,234.5      | 1234.5",
            "AUTO     | 1,234,567    | 1234567",
            "AUTO     | 1.234.567    | 1234567",
            "AUTO     | -1,000       | -1000",
            "AUTO     | 1 234,5      | 1234.5",
            "AUTO     | 1.5e3        | 1500",
            "AUTO     | 12,34,567    | none",
            "ENGLISH  | 1,234        | 1234",
            "ENGLISH  | 1,234.5      | 1234.5",
            "ENGLISH  | 1,5          | none",
            "ENGLISH  | 1.234,5      | none",
            "ENGLISH  | 1 234        | none",
            "EUROPEAN | 1,234        | 1.234",
            "EUROPEAN | 1,5          | 1.5",
            "EUROPEAN | 1.234,5      | 1234.5",
            "EUROPEAN | 1 234,5      | 1234.5",
            "EUROPEAN | 1,234.5      | none",
            "EUROPEAN | 1.5          | none",
    })
    @DisplayName("Each style reads its separators, and fails on the others")
    void reads(NumberStyle style, String value, String expected) {
        Double parsed = PlaceholderModifiers.parseNumber(value, style);
        if (expected == null) {
            assertNull(parsed, value);
        } else {
            assertEquals(Double.parseDouble(expected), parsed, 1e-9, value);
        }
    }

    @Test
    @DisplayName("Narrow and no-break spaces separate thousands, as French writes them")
    void frenchSpaces() {
        assertEquals(1234.5, PlaceholderModifiers.parseNumber("1 234,5", NumberStyle.EUROPEAN));
        assertEquals(1234.5, PlaceholderModifiers.parseNumber("1 234,5", NumberStyle.AUTO));
    }

    @Test
    @DisplayName("The European style writes ',' as the decimal point and '.' between thousands")
    void writes() {
        assertEquals("1.234.567", apply(NumberStyle.EUROPEAN, "1234567", "comma"));
        assertEquals("1.234,50", apply(NumberStyle.EUROPEAN, "1234,5", "comma"));
        assertNull(apply(NumberStyle.EUROPEAN, "1234.5", "comma"), "'.' separates thousands here");
        assertEquals("1.234,50", apply(NumberStyle.EUROPEAN, "1.234,5", "comma"));
        assertEquals("2,50", apply(NumberStyle.EUROPEAN, "2,5", "2f"));
        assertEquals("3,00", apply(NumberStyle.EUROPEAN, "3", "2f"));
        assertEquals("3,5", apply(NumberStyle.EUROPEAN, "2,5", "add_one"));
        assertEquals("4", apply(NumberStyle.EUROPEAN, "2,5", "add:1,5"));
        assertEquals("1,234.50", apply(NumberStyle.AUTO, "1.234,5", "comma"));
        assertEquals("1,234.50", apply(NumberStyle.ENGLISH, "1234.5", "comma"));
    }

    @Test
    @DisplayName("A value grouped by comma reads as its number again (it was read as a decimal)")
    void groupedValue() {
        assertEquals("1235", PlaceholderModifiers.apply("1,234", "add:1"));
        assertEquals("1235.5", PlaceholderModifiers.apply("1,234.5", "add:1"));
        assertEquals("1,234", PlaceholderModifiers.apply("1,234", "comma"));
        assertEquals("1,234", PlaceholderModifiers.apply("1234", "comma.comma"));
    }

    @Test
    @DisplayName("Only digits are numbers: not NaN, Infinity, 2f, 10d or hex")
    void strictNumbers() {
        for (String value : new String[]{"NaN", "Infinity", "-Infinity", "2f", "10d", "0x10p0", "1e", "e5", "--1", "1-", "."}) {
            assertEquals("false", PlaceholderModifiers.apply(value, "isnumber"), value);
            assertNull(PlaceholderModifiers.apply(value, "int"), value);
        }
        assertNull(PlaceholderModifiers.apply("1e400", "int"), "past a double");
        assertEquals("true", PlaceholderModifiers.apply("-1.5e-3", "isnumber"));
    }

    @Test
    @DisplayName("A number past a long fails where a whole part is needed; comma writes it in full")
    void hugeNumbers() {
        for (String chain : new String[]{"int", "round", "floor", "ceil", "even"}) {
            assertNull(PlaceholderModifiers.apply("1e20", chain), chain);
        }
        assertEquals("100,000,000,000,000,000,000", PlaceholderModifiers.apply("1e20", "comma"));
        assertEquals("9,000,000,000,000,000", PlaceholderModifiers.apply("9e15", "comma"));
        assertNull(PlaceholderModifiers.apply("1e308", "add:1e308"), "the sum is past a double");
    }

    @Test
    @DisplayName("max, length, pad and capitalize count characters as shown: an emoji is one")
    void codePoints() {
        String emoji = "😀";
        assertEquals(emoji, PlaceholderModifiers.apply(emoji + "abc", "max:1"));
        assertEquals("1", PlaceholderModifiers.apply(emoji, "length"));
        assertEquals("00" + emoji, PlaceholderModifiers.apply(emoji, "pad:3"));
        assertEquals(emoji + "abc", PlaceholderModifiers.apply(emoji + "ABC", "capitalize"));
        assertNull(PlaceholderModifiers.apply("abc", "max:-1"));
    }

    @Test
    @DisplayName("A modifier registered over a built-in one wins in every style")
    void registeredWins() {
        PlaceholderModifiers.Modifier previous = PlaceholderModifiers.get("comma");
        assertNotNull(previous);
        try {
            ModifierSet european = ModifierSet.builder().numberStyle(NumberStyle.EUROPEAN).add("comma", (v, a) -> "own").build();
            assertEquals("own", european.apply("1", "comma"));
        } finally {
            assertSame(previous, PlaceholderModifiers.get("comma"));
        }
    }
}
