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
}
