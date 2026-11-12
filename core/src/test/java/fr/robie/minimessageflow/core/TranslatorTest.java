package fr.robie.minimessageflow.core;

import fr.robie.minimessageflow.MiniMessageFlow;
import fr.robie.minimessageflow.MiniMessageFlowTranslator;
import fr.robie.minimessageflow.Placeholder;
import fr.robie.minimessageflow.Resolver;
import fr.robie.minimessageflow.testkit.ComponentRuns;
import net.kyori.adventure.identity.Identity;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.kyori.adventure.translation.GlobalTranslator;
import net.kyori.adventure.util.TriState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TranslatorTest {

    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();
    private static final Locale FRENCH_CANADA = Locale.of("fr", "CA");

    private final MiniMessageFlowTranslator translator = MiniMessageFlowTranslator.create(Key.key("test", "messages"), MiniMessageFlow.create());

    @AfterEach
    void removeSource() {
        GlobalTranslator.translator().removeSource(this.translator);
    }

    private String plain(Component translatable, Locale locale) {
        Component translated = this.translator.translate((net.kyori.adventure.text.TranslatableComponent) translatable, locale);
        return translated == null ? null : PLAIN.serialize(translated);
    }

    @Test
    @DisplayName("Arguments in every syntax, inserted as components that keep their style")
    void arguments() {
        this.translator.register("shop.bought", Locale.ENGLISH, "<green>You bought <arg:0> for <argument:1>, %0% <0>");
        Component item = Component.text("Sword", NamedTextColor.GOLD);
        Component translated = this.translator.translate(Component.translatable("shop.bought", item, Component.text("12")), Locale.ENGLISH);
        assertNotNull(translated);
        assertEquals("You bought Sword for 12, Sword Sword", PLAIN.serialize(translated));
        assertTrue(ComponentRuns.of(translated).stream().anyMatch(r -> r.content().equals("Sword") && NamedTextColor.GOLD.equals(r.style().color())));
    }

    @Test
    @DisplayName("Same output as MiniMessage with MiniMessage's argument tags")
    void sameAsMiniMessage() {
        String template = "<green>You bought <arg:0> for <gold><argument:1></gold>!";
        this.translator.register("shop.bought", Locale.ENGLISH, template);
        Component item = Component.text("Sword").decorate(TextDecoration.BOLD);
        Component translated = this.translator.translate(Component.translatable("shop.bought", item, Component.text("12")), Locale.ENGLISH);
        Component expected = MiniMessage.miniMessage().deserialize("<green>You bought <a0> for <gold><a1></gold>!",
                net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.component("a0", item),
                net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.component("a1", Component.text("12")));
        assertEquals(ComponentRuns.of(expected), ComponentRuns.of(translated));
    }

    @Test
    @DisplayName("Locale, then its language, then the default locale, then nothing")
    void localeFallback() {
        this.translator.register("hi", Locale.ENGLISH, "Hello").register("hi", Locale.FRENCH, "Bonjour")
                .register("only.en", Locale.ENGLISH, "English only");
        Component hi = Component.translatable("hi");
        assertEquals("Bonjour", this.plain(hi, Locale.FRENCH));
        assertEquals("Bonjour", this.plain(hi, FRENCH_CANADA), "fr_CA falls back to fr");
        assertNull(this.plain(Component.translatable("only.en"), Locale.GERMAN), "no default locale: nothing");
        this.translator.defaultLocale(Locale.ENGLISH);
        assertEquals("English only", this.plain(Component.translatable("only.en"), Locale.GERMAN));
        assertNull(this.plain(Component.translatable("unknown"), Locale.ENGLISH));
    }

    @Test
    @DisplayName("The translatable's style is a fallback, its children are appended")
    void styleAndChildren() {
        this.translator.register("k", Locale.ENGLISH, "<red>red</red> plain");
        Component translatable = Component.translatable("k", NamedTextColor.BLUE).append(Component.text(" child"));
        Component translated = this.translator.translate((net.kyori.adventure.text.TranslatableComponent) translatable, Locale.ENGLISH);
        assertNotNull(translated);
        assertEquals("red plain child", PLAIN.serialize(translated));
        var runs = ComponentRuns.of(translated);
        assertEquals(NamedTextColor.RED, runs.getFirst().style().color());
        assertEquals(NamedTextColor.BLUE, runs.getLast().style().color(), "the rest takes the translatable's colour");
    }

    @Test
    @DisplayName("The locale is the viewer: a resolver can read it")
    void localeViewer() {
        MiniMessageFlow flow = MiniMessageFlow.builder()
                .resolver((key, viewer) -> key.equals("lang") && viewer != null
                        ? viewer.get(Identity.LOCALE).map(Locale::getLanguage).orElse("?") : null)
                .build();
        MiniMessageFlowTranslator withLocale = MiniMessageFlowTranslator.create(Key.key("test", "locale"), flow)
                .register("which", Locale.FRENCH, "lang=%lang%");
        assertEquals("lang=fr", PLAIN.serialize(withLocale.translate(Component.translatable("which"), Locale.FRENCH)));
    }

    @Test
    @DisplayName("Rendered by Adventure's GlobalTranslator, nested translatables too")
    void globalTranslator() {
        this.translator.registerAll(Locale.ENGLISH, Map.of(
                "greeting", "<yellow>Hello <arg:0>, <lang:farewell>",
                "farewell", "see you"));
        GlobalTranslator.translator().addSource(this.translator);
        Component rendered = GlobalTranslator.render(Component.translatable("greeting", Component.text("Steve")), Locale.ENGLISH);
        assertEquals("Hello Steve, see you", PLAIN.serialize(rendered));
        assertEquals(TriState.TRUE, this.translator.hasAnyTranslations());
        assertNull(this.translator.translate("greeting", Locale.ENGLISH), "no MessageFormat");
    }

    @Test
    @DisplayName("A template that refers to itself stops instead of looping")
    void selfReference() {
        this.translator.register("loop", Locale.ENGLISH, "x<lang:loop>");
        Component translated = this.translator.translate(Component.translatable("loop"), Locale.ENGLISH);
        assertNotNull(translated);
        assertTrue(PLAIN.serialize(translated).startsWith("xxxxxxxxxxxxxxxx"), PLAIN.serialize(translated));
    }

    @Test
    @DisplayName("Registering again replaces; unregister removes every locale")
    void replaceAndRemove() {
        this.translator.register("k", Locale.ENGLISH, "one").register("k", Locale.ENGLISH, "two");
        assertEquals("two", this.plain(Component.translatable("k"), Locale.ENGLISH));
        this.translator.unregister("k");
        assertFalse(this.translator.contains("k"));
        assertNull(this.plain(Component.translatable("k"), Locale.ENGLISH));
        assertEquals(TriState.FALSE, this.translator.hasAnyTranslations());
    }

    @Test
    @DisplayName("Placeholders of the flow work in templates")
    void flowPlaceholders() {
        MiniMessageFlow flow = MiniMessageFlow.builder().resolver(Resolver.of(Placeholder.of("server", "<gold>Lobby"))).build();
        MiniMessageFlowTranslator withGlobals = MiniMessageFlowTranslator.create(Key.key("test", "globals"), flow)
                .register("welcome", Locale.ENGLISH, "Welcome to %server%, <arg:0>");
        assertEquals("Welcome to Lobby, Steve",
                PLAIN.serialize(withGlobals.translate(Component.translatable("welcome", Component.text("Steve")), Locale.ENGLISH)));
    }

    @Test
    @DisplayName("A locale falls back as resource bundles do: script and country dropped, then the root locale")
    void localeCandidates() {
        this.translator.register("fallback", Locale.CHINESE, "zh").register("fallback", Locale.ROOT, "root")
                .register("fallback", Locale.forLanguageTag("zh-Hant"), "zh-Hant");
        Component key = Component.translatable("fallback");
        assertEquals("zh-Hant", this.plain(key, Locale.forLanguageTag("zh-Hant-TW")));
        assertEquals("zh", this.plain(key, Locale.forLanguageTag("zh-Hans-CN")));
        assertEquals("zh", this.plain(key, Locale.forLanguageTag("zh-Hans")));
        assertEquals("root", this.plain(key, Locale.GERMANY));
    }
}
