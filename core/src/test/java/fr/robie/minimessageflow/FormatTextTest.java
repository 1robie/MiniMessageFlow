package fr.robie.minimessageflow;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FormatTextTest {

    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    @Test
    @DisplayName("formatText: placeholders only, values as written, unknown and unclosed % kept")
    void formatText() {
        MiniMessageFlow flow = MiniMessageFlow.create();
        Values values = flow.newValues().set("player", "Steve").setFormatted("rank", "<red>Admin").set("amount", "5");
        assertEquals("/pay Steve 6 <red>Admin %other% 100% <player>",
                flow.formatText("/pay %player% %amount.add:1% %rank% %other% 100% <player>", values, null));
        assertEquals("/pay Alex", flow.formatText("/pay %player%", Placeholder.of("player", "Alex")));
    }

    @Test
    @DisplayName("formatText follows constants added after build")
    void followsChanges() {
        MiniMessageFlow flow = MiniMessageFlow.create();
        assertEquals("%server%", flow.formatText("%server%", Placeholder.empty()));
        flow.constant("server", "Lobby");
        assertEquals("Lobby", flow.formatText("%server%", Placeholder.empty()));
    }

    @Test
    @DisplayName("formatLines renders each line, a value with a break giving two")
    void formatLines() {
        MiniMessageFlow flow = MiniMessageFlow.create();
        flow.tag("price", Tag.preProcessParsed("<gold>"));
        Values values = flow.newValues().set("desc", "Sharp\nShiny").set("cost", "10");
        List<Component> lines = flow.formatLines(List.of("%desc%", "<price>%cost%"), values, null);
        assertEquals(List.of("Sharp", "Shiny", "10"), lines.stream().map(PLAIN::serialize).toList());
        assertEquals(List.of("Sharp", "Shiny"),
                flow.formatLines(List.of("%desc%"), Placeholder.of("desc", "Sharp\nShiny"), null).stream().map(PLAIN::serialize).toList());
    }
}
