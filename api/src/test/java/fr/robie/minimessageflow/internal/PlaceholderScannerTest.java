package fr.robie.minimessageflow.internal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Placeholder scanner")
class PlaceholderScannerTest {
    private static final Map<String, String> VALUES = Map.of("a", "1", "player", "Steve");

    private static String replace(String input) {
        return PlaceholderScanner.replace(input, (key, start) -> VALUES.get(key));
    }

    @Test
    @DisplayName("Replaces known tokens and keeps unknown ones")
    void replaces() {
        assertEquals("1-Steve", replace("%a%-%player%"));
        assertEquals("%b% 1", replace("%b% %a%"));
        assertEquals("1%", replace("%a%%"));
        assertEquals("100% sure Steve", replace("100% sure %player%"));
        assertEquals("%% %", replace("%% %"));
        assertEquals("%vault_eco_balance%a%", replace("%vault_eco_balance%a%"), "an unknown key keeps its closing %");
        assertEquals("50% of 1", replace("50% of %a%"));
        assertEquals("", replace(""));
    }

    @Test
    @DisplayName("Returns the same instance when nothing changes")
    void sameInstance() {
        String text = "no tokens %here%";
        assertSame(text, replace(text));
    }

    @Test
    @DisplayName("Gives the position of each token")
    void positions() {
        List<Integer> starts = new ArrayList<>();
        PlaceholderScanner.replace("x%a% %b%", (key, start) -> {
            starts.add(start);
            return null;
        });
        assertEquals(List.of(1, 5), starts, "the % closing an unknown key does not open a token");
    }

    @Test
    @DisplayName("Finds a matching token")
    void anyKey() {
        assertTrue(PlaceholderScanner.anyKey("50% off %player%", VALUES::containsKey));
        assertFalse(PlaceholderScanner.anyKey("50% off %nobody%", VALUES::containsKey));
        assertFalse(PlaceholderScanner.anyKey("no tokens", VALUES::containsKey));
        assertFalse(PlaceholderScanner.anyKey("%unknown%a%", VALUES::containsKey), "a closing % is not reused");
    }
}
