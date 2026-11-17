package fr.robie.minimessageflow.paper;

import fr.robie.minimessageflow.MiniMessageFlow;
import fr.robie.minimessageflow.Placeholder;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class PaperMessagesTest {

    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    @TempDir
    Path folder;

    private final List<LogRecord> logs = new ArrayList<>();
    private final Logger logger = Fakes.recordingLogger(this.logs);
    private final MiniMessageFlow flow = MiniMessageFlow.create();

    private File write(String yaml) throws IOException {
        Path file = this.folder.resolve("messages.yml");
        Files.writeString(file, yaml);
        return file.toFile();
    }

    @Test
    @DisplayName("Every message by its full key; a list is one message, one line each")
    void load() throws IOException {
        PaperMessages messages = PaperMessages.load(this.write("""
                prefix: "<gray>[Shop]"
                shop:
                  bought: "<green>You bought %item%"
                  help:
                    - "Line one"
                    - "Line two"
                  amount: 3
                """), this.flow, this.logger);
        assertEquals("You bought Sword", PLAIN.serialize(messages.format("shop.bought", Placeholder.of("item", "Sword"), null)));
        assertEquals("Line one\nLine two", PLAIN.serialize(messages.format("shop.help", null)));
        assertEquals(List.of("prefix", "shop.bought", "shop.help"), List.copyOf(messages.templates().keySet()),
                "sections and numbers are not messages");
    }

    @Test
    @DisplayName("A missing key gives its name and warns once")
    void missing() throws IOException {
        PaperMessages messages = PaperMessages.load(this.write("a: x"), this.flow, this.logger);
        assertEquals("nope", PLAIN.serialize(messages.format("nope", null)));
        assertEquals("nope", PLAIN.serialize(messages.format("nope", null)));
        assertEquals(1, this.logs.size());
        assertNull(messages.template("nope"));
    }

    @Test
    @DisplayName("reload takes the file's new messages; invalid YAML keeps the old ones")
    void reload() throws IOException {
        File file = this.write("a: one");
        PaperMessages messages = PaperMessages.load(file, this.flow, this.logger);
        Files.writeString(file.toPath(), "a: two");
        messages.reload();
        assertEquals("two", PLAIN.serialize(messages.format("a", null)));
        Files.writeString(file.toPath(), "a: [unclosed");
        assertThrows(IllegalStateException.class, messages::reload);
        assertEquals("two", PLAIN.serialize(messages.format("a", null)));
    }

    @Test
    @DisplayName("A file that does not exist loads no message and warns")
    void noFile() {
        PaperMessages messages = PaperMessages.load(this.folder.resolve("none.yml").toFile(), this.flow, this.logger);
        assertTrue(messages.templates().isEmpty());
        assertEquals(1, this.logs.size());
    }
}
