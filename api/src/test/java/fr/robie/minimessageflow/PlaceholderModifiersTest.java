package fr.robie.minimessageflow;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Placeholder modifiers")
class PlaceholderModifiersTest {

    private static String parse(String message, Placeholder placeholders) {
        return placeholders.parse(message);
    }

    @Test
    @DisplayName("Text modifiers, chained")
    void text() {
        Placeholder p = Placeholder.of("name", "steve", "empty", "");
        assertEquals("STEVE", parse("%name.upper%", p));
        assertEquals("Steve", parse("%name.capitalize%", p));
        assertEquals("5", parse("%name.length%", p));
        assertEquals("NOBODY", parse("%empty.default:Nobody.upper%", p));
        assertEquals("ste", parse("%name.max:3%", p));
        assertEquals("steve", parse("%name%", p));
    }

    @Test
    @DisplayName("Number modifiers")
    void numbers() {
        Placeholder p = Placeholder.of("price", "1234.5", "n", "7");
        assertEquals("1,234.50", parse("%price.comma%", p));
        assertEquals("1234.50", parse("%price.2f%", p));
        assertEquals("1235", parse("%price.round%", p));
        assertEquals("1236", parse("%price.add:1.5%", p));
        assertEquals("8", parse("%n.add_one%", p));
        assertEquals("007", parse("%n.pad:3%", p));
        assertEquals("false", parse("%n.even%", p));
    }

    @Test
    @DisplayName("Plurals, with an optional zero form")
    void plurals() {
        for (String[] c : new String[][]{{"0", "items", "objet"}, {"1", "item", "objet"}, {"2", "items", "objets"}, {"-1", "item", "objet"}}) {
            Placeholder p = Placeholder.of("count", c[0]);
            assertEquals(c[1], parse("%count.plural:item:items%", p), c[0]);
            assertEquals(c[2], parse("%count.plural:objet:objet:objets%", p), c[0]);
        }
    }

    @Test
    @DisplayName("An unknown or failing modifier keeps the token")
    void keepsToken() {
        Placeholder p = Placeholder.of("name", "Steve");
        assertEquals("%name.nope%", parse("%name.nope%", p));
        assertEquals("%name.int%", parse("%name.int%", p));
        assertEquals("%other.upper%", parse("%other.upper%", p));
        assertEquals("%name.pad:100000%", parse("%name.pad:100000%", p), "a pad wider than 256 fails");
    }

    @Test
    @DisplayName("A capitalized word after a dot belongs to the argument, not a modifier")
    void argumentWithModifierName() {
        Placeholder p = Placeholder.of("title", "");
        assertEquals("Mr.Length", parse("%title.default:Mr.Length%", p));
        assertEquals("MR.X", parse("%title.default:Mr.X.upper%", p));
    }

    @Test
    @DisplayName("A key with a dot is read as is first")
    void dottedKey() {
        Placeholder p = Placeholder.of("server.name", "Lobby", "server", "ignored");
        assertEquals("Lobby", parse("%server.name%", p));
        assertEquals("LOBBY", parse("%server.name.upper%", p));
    }

    @Test
    @DisplayName("Custom modifiers can be registered")
    void custom() {
        PlaceholderModifiers.register("reverse", (v, a) -> new StringBuilder(v).reverse().toString());
        assertEquals("evets", parse("%name.reverse%", Placeholder.of("name", "steve")));
    }

    @Test
    @DisplayName("A player placeholder with modifiers is still player dependent")
    void playerDependent() {
        Placeholder p = Placeholder.of("health", player -> "20");
        assertTrue(p.isViewerDependent("%health.int%"));
        assertFalse(p.isViewerDependent("%other.int%"));
    }

    @Test
    @DisplayName("Whole numbers are grouped as String.format groups them")
    void grouped() {
        long[] edges = {0, 1, -1, 12, 123, 999, 1000, -1000, 1234, 12345, 123456, 1234567, -1234567,
                1001, -1001, 100_000, -100_000, 999_999, -999_999, 1_000_000, -1_000_000, Long.MAX_VALUE, Long.MIN_VALUE};
        for (long n : edges) {
            assertEquals(String.format(java.util.Locale.US, "%,d", n), PlaceholderModifiers.grouped(n));
        }
        java.util.Random random = new java.util.Random(42);
        for (int i = 0; i < 5000; i++) {
            long n = random.nextLong() >> random.nextInt(64);
            assertEquals(String.format(java.util.Locale.US, "%,d", n), PlaceholderModifiers.grouped(n));
        }
        assertEquals("1,250", parse("%price.comma%", Placeholder.of("price", "1250")));
        assertEquals("1,250.50", parse("%price.comma%", Placeholder.of("price", "1250.5")));
    }

    @Test
    @DisplayName("A whole number read without a double gives what the same number read as a double gives")
    void wholeNumbers() {
        String[] modifiers = {"int", "round", "floor", "ceil", "abs", "negative", "even", "0f", "1f", "2f", "3f", "comma",
                "add_one", "remove_one", "add:5", "sub:7", "add:-999999999999999", "sub:-999999999999999",
                "plural:one:other", "plural:zero:one:other", "isnumber"};
        String[] numbers = {"0", "-0", "+0", "1", "-1", "+1", "2", "7", "-7", "007", "999", "1000", "-1000", "1250",
                "123456", "999999", "1000000", "-1234567", "999999999999998", "999999999999999", "-999999999999999",
                "1000000000000000", "-1000000000000000", "12345678901234567890"};
        for (String modifier : modifiers) {
            for (String number : numbers) {
                // With ".0" the text is not one whole(...) reads: it goes through Double.parseDouble.
                String asDouble = PlaceholderModifiers.apply(number + ".0", modifier);
                assertEquals(asDouble, PlaceholderModifiers.apply(number, modifier), number + "." + modifier);
                assertEquals(asDouble, PlaceholderModifiers.apply("  " + number + " ", modifier), "spaces around " + number + "." + modifier);
            }
        }
        assertEquals(1250, PlaceholderModifiers.whole("1250"));
        assertEquals(-3, PlaceholderModifiers.whole(" -3\t"));
        assertEquals(999_999_999_999_999L, PlaceholderModifiers.whole("999999999999999"));
        for (String other : new String[]{"", " ", "-", "+", "-0", "1.5", "1,5", "1e3", "5d", "0x10", "1 2", "--1", "١٢", "1000000000000000"}) {
            assertEquals(PlaceholderModifiers.NOT_WHOLE, PlaceholderModifiers.whole(other), other);
        }
    }

    @Test
    @DisplayName("A chain read once gives what reading it each time gives")
    void compiledChain() {
        String[] chains = {"upper", "comma", "default:0.upper", "add:1.5", "max:3.upper", "default:Mr.Length", "plural:a:b"};
        String[] values = {"steve", "1250", "", "2", "1"};
        for (String chain : chains) {
            PlaceholderModifiers.Chain compiled = PlaceholderModifiers.compile(chain);
            assertNotNull(compiled, chain);
            for (String value : values) {
                assertEquals(PlaceholderModifiers.apply(value, chain), compiled.apply(value), chain + " on " + value);
                assertEquals(compiled.apply(value), compiled.apply(value), "applied twice: " + chain);
            }
        }
        assertNull(PlaceholderModifiers.compile("nosuchmodifier"));
        assertNull(PlaceholderModifiers.compile("upper.nosuchmodifier:x"));
    }
}
