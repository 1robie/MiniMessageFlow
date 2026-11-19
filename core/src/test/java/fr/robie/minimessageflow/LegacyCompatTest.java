package fr.robie.minimessageflow;

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LegacyCompatTest {

    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    private static MiniMessageFlow zMenuLike() {
        return MiniMessageFlow.builder().legacyPrefixModifiers(true).failedModifier(FailedModifier.EMPTY).build();
    }

    private static String render(MiniMessageFlow flow, String message, Values values) {
        return PLAIN.serialize(flow.compile(message).render(values));
    }

    @Test
    @DisplayName("Every legacy prefix reads the rest of the key with its modifier")
    void prefixes() {
        MiniMessageFlow flow = zMenuLike();
        Values values = flow.newValues().set("name", "sTEVE").set("level", "5");
        assertEquals("STEVE steve STEVE 6 4", render(flow,
                "%upper_name% %lower_name% %capitalize_name% %add_one_level% %remove_one_level%", values));
        assertEquals("/give 4", flow.compileText("/give %remove_one_level%").render(values, null));
    }

    @Test
    @DisplayName("capitalize_ keeps the rest's case; .capitalize lowers it")
    void capitalize() {
        MiniMessageFlow flow = zMenuLike();
        Values values = flow.newValues().set("name", "mcDonald");
        assertEquals("McDonald Mcdonald", render(flow, "%capitalize_name% %name.capitalize%", values));
    }

    @Test
    @DisplayName("A value set for the prefixed key itself wins")
    void ownValueFirst() {
        MiniMessageFlow flow = zMenuLike();
        Values values = flow.newValues().set("name", "steve").set("upper_name", "mine");
        assertEquals("mine", render(flow, "%upper_name%", values));
    }

    @Test
    @DisplayName("Without the option, prefixes are plain keys")
    void offByDefault() {
        MiniMessageFlow flow = MiniMessageFlow.create();
        Values values = flow.newValues().set("name", "steve");
        assertEquals("%upper_name%", render(flow, "%upper_name%", values));
    }

    @Test
    @DisplayName("EMPTY: an unknown or failing modifier gives the empty text; a placeholder without value stays")
    void emptyOnFailure() {
        MiniMessageFlow flow = zMenuLike();
        Values values = flow.newValues().set("name", "steve");
        assertEquals("[] [] [%missing.upper%] [%nobody%]", render(flow,
                "[%name.nope%] [%name.int%] [%missing.upper%] [%nobody%]", values));
        assertEquals("[]", PLAIN.serialize(flow.compile("[%name.int%]").render(Placeholder.of("name", "steve"))));
        assertEquals("[]", flow.compileText("[%name.nope%]").render(values, null));
    }

    @Test
    @DisplayName("WRITTEN, the default: a failing modifier keeps the placeholder as written")
    void writtenByDefault() {
        MiniMessageFlow flow = MiniMessageFlow.create();
        Values values = flow.newValues().set("name", "steve");
        assertEquals("[%name.nope%] [%name.int%]", render(flow, "[%name.nope%] [%name.int%]", values));
    }

    @Test
    @DisplayName("EMPTY applies to lookups, resolvers and constants too")
    void emptyEverywhere() {
        MiniMessageFlow flow = MiniMessageFlow.builder().failedModifier(FailedModifier.EMPTY)
                .resolver((key, viewer) -> key.equals("server") ? "Lobby" : null)
                .constant("prefix", "Shop")
                .build();
        Values values = flow.newValues();
        // As zMenu's canvas lookup: null for a dotted key, so the modifiers apply to its first part.
        values.lookup("canvas_", rest -> rest.indexOf('.') >= 0 ? null : "tab");
        assertEquals("[] [] []", render(flow, "[%canvas_x.int%] [%server.int%] [%prefix.int%]", values));
    }
}
