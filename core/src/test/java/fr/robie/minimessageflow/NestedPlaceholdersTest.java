package fr.robie.minimessageflow;

import fr.robie.minimessageflow.internal.PlaceholderScanner;
import net.kyori.adventure.pointer.Pointered;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class NestedPlaceholdersTest {

    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    /**
     * A stand-in for PlaceholderAPI's ChangeOutput expansion: {@code changeoutput_equals_input:A_matcher:B_ifmatch:X_else:Y}.
     */
    private static final class ChangeOutput implements Resolver {
        final List<String> asked = new ArrayList<>();

        @Override
        public @Nullable String resolve(@NotNull String key, @Nullable Pointered viewer) {
            this.asked.add(key);
            if (!key.startsWith("changeoutput_equals_input:")) {
                return null;
            }
            String rest = key.substring("changeoutput_equals_input:".length());
            String input = rest.substring(0, rest.indexOf("_matcher:"));
            rest = rest.substring(rest.indexOf("_matcher:") + "_matcher:".length());
            String matcher = rest.substring(0, rest.indexOf("_ifmatch:"));
            rest = rest.substring(rest.indexOf("_ifmatch:") + "_ifmatch:".length());
            String ifMatch = rest.substring(0, rest.indexOf("_else:"));
            String orElse = rest.substring(rest.indexOf("_else:") + "_else:".length());
            return input.equals(matcher) ? ifMatch : orElse;
        }
    }

    private static final String TITLE = "<white>%changeoutput_equals_input:%page%_matcher:1_ifmatch:<red>FIRST_else:<white>%"
            + "%changeoutput_equals_input:%page%_matcher:%max-page%_ifmatch:<red>LAST_else:<white>%";

    @Test
    @DisplayName("Finds a placeholder inside another one, and only then")
    void detection() {
        assertTrue(PlaceholderScanner.hasNested(TITLE));
        assertTrue(PlaceholderScanner.hasNested("%math_0_%page%+1%"));
        assertFalse(PlaceholderScanner.hasNested("Page %page% of %max-page%"));
        assertFalse(PlaceholderScanner.hasNested("Price:%price%$ and 100% sure"));
        assertFalse(PlaceholderScanner.hasNested("%vault_eco_balance%coins%"));
        assertFalse(PlaceholderScanner.hasNested("%a% %b%"));
    }

    @Test
    @DisplayName("Values go in first, then the resolvers read the placeholders they complete, tags included")
    void titleFromValues() {
        ChangeOutput changeOutput = new ChangeOutput();
        MiniMessageFlow flow = MiniMessageFlow.builder().resolver(changeOutput).build();
        Template template = flow.compile(TITLE);
        assertFalse(template.isStatic());

        Values first = flow.newValues().set("page", "1").set("max-page", "10");
        assertEquals("FIRST", PLAIN.serialize(template.render(first, null)));
        Values last = flow.newValues().set("page", "10").set("max-page", "10");
        assertEquals("LAST", PLAIN.serialize(template.render(last, null)));
        Values middle = flow.newValues().set("page", "4").set("max-page", "10");
        assertEquals("", PLAIN.serialize(template.render(middle, null)));
        assertFalse(changeOutput.asked.contains("changeoutput_equals_input:"));

        assertEquals("FIRST", PLAIN.serialize(template.render(Placeholder.of("page", "1", "max-page", "10"), null)));
        assertEquals("FIRST", PLAIN.serialize(flow.format(TITLE, first, null)));
    }

    @Test
    @DisplayName("A value a player typed stays as written: its tags and placeholders are never read")
    void rawValues() {
        MiniMessageFlow flow = MiniMessageFlow.builder()
                .resolver((key, viewer) -> key.startsWith("echo_") ? key.substring(5) : null)
                .resolver((key, viewer) -> key.equals("secret") ? "LEAK" : null)
                .build();
        Template template = flow.compile("%echo_%name%_end%");
        Values typed = flow.newValues().set("name", "<red>%secret%");
        assertEquals("<red>%secret%_end", PLAIN.serialize(template.render(typed, null)));
        Values formatted = flow.newValues().setFormatted("name", "<red>bob");
        assertEquals("bob_end", PLAIN.serialize(template.render(formatted, null)));
    }

    @Test
    @DisplayName("As text: a command with a placeholder inside another")
    void text() {
        MiniMessageFlow flow = MiniMessageFlow.builder()
                .resolver((key, viewer) -> key.startsWith("math_0_") ? String.valueOf(Integer.parseInt(key.substring(7).replace("+1", "")) + 1) : null)
                .build();
        TextTemplate text = flow.compileText("give %player% diamond %math_0_%amount%+1%");
        assertEquals("give %player% diamond 5", text.render(flow.newValues().set("amount", "4"), null));
    }
}
