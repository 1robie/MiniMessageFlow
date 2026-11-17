package fr.robie.minimessageflow.paper;

import fr.robie.minimessageflow.MiniMessageFlow;
import fr.robie.minimessageflow.NumberStyle;
import fr.robie.minimessageflow.Placeholder;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.LogRecord;

import static org.junit.jupiter.api.Assertions.*;

class PaperFlowTest {

    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();
    private final List<LogRecord> logs = new ArrayList<>();
    private final Player steve = Fakes.player("Steve", UUID.randomUUID());

    static {
        Fakes.ONLINE.size(); // sets Bukkit's server
    }

    @Test
    @DisplayName("One call: the built-in placeholders, your setup first, errors logged once to the plugin")
    void build() {
        MiniMessageFlow flow = PaperFlow.builder(Fakes.plugin(Fakes.recordingLogger(this.logs)))
                .configure(builder -> builder.numberStyle(NumberStyle.EUROPEAN)
                        .valueTag("viewer_name", (arguments, viewer) -> "mine")
                        .resolver((key, viewer) -> {
                            if (key.equals("broken")) {
                                throw new IllegalStateException("down");
                            }
                            return null;
                        }))
                .build();
        assertEquals("mine 100 %broken% %broken%", PLAIN.serialize(
                flow.format("<viewer_name> <server_max> %broken% %broken%", Placeholder.empty(), this.steve)));
        flow.format("%broken%");
        assertEquals(1, this.logs.size(), "a failing placeholder is logged once");
        assertEquals("1.234,50", PLAIN.serialize(flow.format("%v.comma%", Placeholder.of("v", "1234,5"))));
    }

    @Test
    @DisplayName("Each part can be turned off")
    void off() {
        MiniMessageFlow flow = PaperFlow.builder(Fakes.plugin(Fakes.recordingLogger(this.logs)))
                .placeholders(false)
                .placeholderApi(false)
                .build();
        assertEquals("<viewer_name>", PLAIN.serialize(flow.format("<viewer_name>", Placeholder.empty(), this.steve)));
    }
}
