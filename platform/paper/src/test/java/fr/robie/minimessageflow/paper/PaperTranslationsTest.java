package fr.robie.minimessageflow.paper;

import fr.robie.minimessageflow.MiniMessageFlow;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.kyori.adventure.translation.GlobalTranslator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class PaperTranslationsTest {

    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    @TempDir
    Path folder;

    private final List<LogRecord> logs = new ArrayList<>();
    private final Logger logger = Fakes.recordingLogger(this.logs);
    private PaperTranslations translations;

    @AfterEach
    void close() {
        if (this.translations != null) {
            this.translations.close();
        }
    }

    private void write(String file, String yaml) throws IOException {
        Files.writeString(this.folder.resolve(file), yaml);
    }

    private PaperTranslations load(Locale defaultLocale) {
        this.translations = PaperTranslations.builder(MiniMessageFlow.create())
                .name(Key.key("test", "translations"))
                .defaultLocale(defaultLocale)
                .load(this.folder.toFile(), this.logger);
        return this.translations;
    }

    private static String render(Component component, Locale locale) {
        return PLAIN.serialize(GlobalTranslator.render(component, locale));
    }

    @Test
    @DisplayName("Each file is its locale's; a player's locale falls back to its language, then the default")
    void locales() throws IOException {
        this.write("en_US.yml", "shop:\n  bought: \"<green>You bought <arg:0>\"\nhello: Hello\n");
        this.write("fr.yml", "shop:\n  bought: \"<green>Tu as acheté %0%\"\n");
        this.load(Locale.US);
        Component bought = Component.translatable("shop.bought", Component.text("Sword"));
        assertEquals("You bought Sword", render(bought, Locale.US));
        assertEquals("Tu as acheté Sword", render(bought, Locale.CANADA_FRENCH), "fr_CA falls back to fr");
        assertEquals("You bought Sword", render(bought, Locale.GERMANY), "no German file: the default locale");
        assertEquals("Hello", render(Component.translatable("hello"), Locale.FRANCE), "no French one: the default");
    }

    @Test
    @DisplayName("reload takes the new files; close removes the translations")
    void reloadAndClose() throws IOException {
        this.write("en.yml", "hello: Hello");
        PaperTranslations translations = this.load(null);
        this.write("en.yml", "hello: Hi");
        translations.reload();
        assertEquals("Hi", render(Component.translatable("hello"), Locale.ENGLISH));
        translations.close();
        assertEquals("hello", render(Component.translatable("hello"), Locale.ENGLISH), "untranslated: its key");
    }

    @Test
    @DisplayName("Without a file, nothing is translated, and the logger warns")
    void noFiles() {
        this.load(null);
        assertEquals(1, this.logs.size());
        assertEquals("hello", render(Component.translatable("hello"), Locale.ENGLISH));
    }

    @Test
    @DisplayName("A file's name gives its locale: en_US, en, a language tag")
    void fileNames() {
        assertEquals(Locale.US, PaperTranslations.locale("en_US"));
        assertEquals(Locale.ENGLISH, PaperTranslations.locale("en"));
        assertEquals(Locale.forLanguageTag("zh-Hant-TW"), PaperTranslations.locale("zh-Hant-TW"));
    }
}
