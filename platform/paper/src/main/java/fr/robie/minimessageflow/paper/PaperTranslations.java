package fr.robie.minimessageflow.paper;

import fr.robie.minimessageflow.MiniMessageFlow;
import fr.robie.minimessageflow.MiniMessageFlowTranslator;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.translation.GlobalTranslator;
import net.kyori.adventure.translation.Translator;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.logging.Logger;

/**
 * Translation files, for players in their own language: {@code lang/en_US.yml}, {@code lang/fr_FR.yml}... each the
 * messages of the locale its name gives, registered in Adventure's {@link GlobalTranslator}. Paper then renders a
 * {@code Component.translatable("shop.bought", item)} in each player's language: no viewer to pass.
 * <pre>{@code
 * PaperTranslations translations = PaperTranslations.builder(plugin, flow)
 *         .folder("lang")                           // in the data folder
 *         .defaults("en_US.yml", "fr_FR.yml")      // copied from the jar when missing
 *         .defaultLocale(Locale.US)                // for a language without a file
 *         .load();
 * player.sendMessage(Component.translatable("shop.bought", Component.text("Sword")));
 * translations.reload();                           // on your /reload
 * translations.close();                            // in onDisable
 * }</pre>
 * Nothing of this is set up unless a plugin loads it. A translation's arguments are {@code <arg:0>}, {@code %0%} or
 * {@code <0>} (see {@link MiniMessageFlowTranslator}). A file's locale is its name: {@code en_US}, {@code en},
 * {@code zh-Hant-TW} (a language tag); a player's locale falls back to its language, then to the default locale.
 */
public final class PaperTranslations implements AutoCloseable {

    private final Key name;
    private final File folder;
    private final @Nullable Plugin plugin;
    private final String folderPath;
    private final List<String> defaults;
    private final @Nullable Locale defaultLocale;
    private final MiniMessageFlow flow;
    private final Logger logger;
    private volatile @Nullable MiniMessageFlowTranslator registered;

    private PaperTranslations(@NotNull Builder builder, @NotNull File folder, @NotNull Logger logger) {
        this.name = builder.name();
        this.folder = folder;
        this.plugin = builder.plugin;
        this.folderPath = builder.folder;
        this.defaults = List.copyOf(builder.defaults);
        this.defaultLocale = builder.defaultLocale;
        this.flow = builder.flow;
        this.logger = logger;
    }

    public static @NotNull Builder builder(@NotNull Plugin plugin, @NotNull MiniMessageFlow flow) {
        return new Builder(Objects.requireNonNull(plugin, "plugin cannot be null"), Objects.requireNonNull(flow, "flow cannot be null"));
    }

    /**
     * A builder for a folder, without a plugin (for tests).
     */
    static @NotNull Builder builder(@NotNull MiniMessageFlow flow) {
        return new Builder(null, flow);
    }

    public static final class Builder {
        private final @Nullable Plugin plugin;
        private final MiniMessageFlow flow;
        private String folder = "lang";
        private final List<String> defaults = new ArrayList<>();
        private @Nullable Locale defaultLocale;
        private @Nullable Key key;

        private Builder(@Nullable Plugin plugin, @NotNull MiniMessageFlow flow) {
            this.plugin = plugin;
            this.flow = flow;
        }

        /**
         * The folder, in the plugin's data folder: {@code lang} by default.
         */
        public @NotNull Builder folder(@NotNull String folder) {
            this.folder = Objects.requireNonNull(folder, "folder cannot be null");
            return this;
        }

        /**
         * Files of the plugin's jar, in the folder there ({@code lang/en_US.yml}), copied to the data folder when
         * missing: the translations a server starts with, which its owner can then change.
         */
        public @NotNull Builder defaults(@NotNull String @NotNull ... files) {
            this.defaults.addAll(List.of(files));
            return this;
        }

        /**
         * The locale used for a player whose language has no file. None by default: such a translatable is left to
         * the other translators.
         */
        public @NotNull Builder defaultLocale(@Nullable Locale locale) {
            this.defaultLocale = locale;
            return this;
        }

        /**
         * The translator's name among the {@link GlobalTranslator}'s: {@code <plugin>:translations} by default.
         */
        public @NotNull Builder name(@NotNull Key name) {
            this.key = Objects.requireNonNull(name, "name cannot be null");
            return this;
        }

        @NotNull Key name() {
            if (this.key != null) {
                return this.key;
            }
            String plugin = this.plugin == null ? "minimessageflow" : this.plugin.getName().toLowerCase(Locale.ROOT)
                    .replaceAll("[^a-z0-9_.-]", "_");
            return Key.key(plugin.isEmpty() ? "minimessageflow" : plugin, "translations");
        }

        /**
         * Reads the files and registers their messages in the {@link GlobalTranslator}.
         *
         * @throws IllegalStateException when a file can't be read or is not valid YAML; nothing is registered
         */
        public @NotNull PaperTranslations load() {
            Objects.requireNonNull(this.plugin, "a plugin's translations need the plugin");
            PaperTranslations translations = new PaperTranslations(this, new File(this.plugin.getDataFolder(), this.folder),
                    this.plugin.getLogger());
            translations.reload();
            return translations;
        }

        /**
         * As {@link #load}, from a folder (for tests).
         */
        @NotNull PaperTranslations load(@NotNull File folder, @NotNull Logger logger) {
            PaperTranslations translations = new PaperTranslations(this, folder, logger);
            translations.reload();
            return translations;
        }
    }

    /**
     * Reads the files again and registers their messages in place of the old ones, at once.
     *
     * @throws IllegalStateException when a file can't be read or is not valid YAML; the old translations stay
     */
    public void reload() {
        if (this.plugin != null) {
            for (String file : this.defaults) {
                String path = this.folderPath + "/" + file;
                if (!new File(this.folder, file).exists() && this.plugin.getResource(path) != null) {
                    this.plugin.saveResource(path, false);
                }
            }
        }
        MiniMessageFlowTranslator translator = MiniMessageFlowTranslator.create(this.name, this.flow)
                .defaultLocale(this.defaultLocale);
        Map<Locale, Integer> counts = new TreeMap<>(java.util.Comparator.comparing(Locale::toString));
        File[] files = this.folder.listFiles((dir, file) -> file.endsWith(".yml") || file.endsWith(".yaml"));
        if (files == null || files.length == 0) {
            this.logger.warning("no translation file in " + this.folder);
        } else {
            for (File file : files) {
                Locale locale = locale(file.getName().substring(0, file.getName().lastIndexOf('.')));
                Map<String, String> messages = YamlMessages.read(file);
                translator.registerAll(locale, messages);
                counts.merge(locale, messages.size(), Integer::sum);
            }
        }
        // The new translator is added before the old one is removed: a render in between finds one of them.
        GlobalTranslator.translator().addSource(translator);
        MiniMessageFlowTranslator old = this.registered;
        this.registered = translator;
        if (old != null) {
            GlobalTranslator.translator().removeSource(old);
        }
        this.logger.fine(() -> "translations loaded: " + counts);
    }

    /**
     * The locale a file's name gives: {@code en_US}, {@code en}, or a language tag ({@code zh-Hant-TW}).
     */
    static @NotNull Locale locale(@NotNull String name) {
        if (name.indexOf('-') >= 0) {
            return Locale.forLanguageTag(name);
        }
        String[] parts = name.split("_", 3);
        return switch (parts.length) {
            case 1 -> Locale.of(parts[0]);
            case 2 -> Locale.of(parts[0], parts[1]);
            default -> Locale.of(parts[0], parts[1], parts[2]);
        };
    }

    /**
     * The translator registered now, for direct use.
     */
    public @Nullable Translator translator() {
        return this.registered;
    }

    /**
     * Removes the translations from the {@link GlobalTranslator}: call it when the plugin is disabled.
     */
    @Override
    public void close() {
        MiniMessageFlowTranslator old = this.registered;
        this.registered = null;
        if (old != null) {
            GlobalTranslator.translator().removeSource(old);
        }
    }
}
