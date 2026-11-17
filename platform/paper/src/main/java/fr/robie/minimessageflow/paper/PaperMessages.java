package fr.robie.minimessageflow.paper;

import fr.robie.minimessageflow.MiniMessageFlow;
import fr.robie.minimessageflow.Placeholder;
import fr.robie.minimessageflow.Template;
import net.kyori.adventure.pointer.Pointered;
import net.kyori.adventure.text.Component;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The messages of a YAML file, compiled once: {@code shop.bought: "<green>You bought %item%"} is the template
 * {@code shop.bought}. A list of lines is one message, its lines joined with new lines.
 * <pre>{@code
 * PaperMessages messages = PaperMessages.load(plugin, "messages.yml", flow);
 * player.sendMessage(messages.format("shop.bought", Placeholder.of("item", "Sword"), player));
 * // on /reload of your plugin:
 * messages.reload();
 * }</pre>
 * The file is in the plugin's data folder; when it is missing and the plugin's jar has one by that name, that one is
 * copied there first. Thread-safe: a render reads the messages of the last load, and {@link #reload} replaces them at
 * once.
 */
public final class PaperMessages {

    private final File file;
    private final @Nullable Plugin plugin;
    private final String resource;
    private final MiniMessageFlow flow;
    private final Logger logger;
    private volatile Map<String, Template> templates = Map.of();
    /**
     * The missing keys already logged, so one asked at each send warns once.
     */
    private final Set<String> warnedMissing = ConcurrentHashMap.newKeySet();

    private PaperMessages(@NotNull File file, @Nullable Plugin plugin, @NotNull String resource,
                          @NotNull MiniMessageFlow flow, @NotNull Logger logger) {
        this.file = file;
        this.plugin = plugin;
        this.resource = resource;
        this.flow = flow;
        this.logger = logger;
    }

    /**
     * Loads and compiles the messages of a file in the plugin's data folder.
     *
     * @param plugin the plugin: its data folder, its jar (for a default file), its logger
     * @param path   the file's path in the data folder, and of the default in the jar ({@code messages.yml},
     *               {@code lang/en.yml})
     * @param flow   compiles the messages
     * @throws IllegalStateException when the file can't be read or is not valid YAML
     */
    public static @NotNull PaperMessages load(@NotNull Plugin plugin, @NotNull String path, @NotNull MiniMessageFlow flow) {
        Objects.requireNonNull(plugin, "plugin cannot be null");
        Objects.requireNonNull(path, "path cannot be null");
        Objects.requireNonNull(flow, "flow cannot be null");
        PaperMessages messages = new PaperMessages(new File(plugin.getDataFolder(), path), plugin, path, flow, plugin.getLogger());
        messages.reload();
        return messages;
    }

    /**
     * Loads and compiles the messages of a file, without a plugin (for tests).
     */
    static @NotNull PaperMessages load(@NotNull File file, @NotNull MiniMessageFlow flow, @NotNull Logger logger) {
        PaperMessages messages = new PaperMessages(file, null, file.getName(), flow, logger);
        messages.reload();
        return messages;
    }

    /**
     * Reads the file again and compiles its messages; the old ones are used until the new ones are ready. Messages
     * that can't use the fast path are logged at {@code FINE}, with the reason.
     *
     * @throws IllegalStateException when the file can't be read or is not valid YAML; the old messages stay
     */
    public void reload() {
        if (!this.file.exists() && this.plugin != null && this.plugin.getResource(this.resource) != null) {
            this.plugin.saveResource(this.resource, false);
        }
        YamlConfiguration yaml = new YamlConfiguration();
        if (this.file.exists()) {
            try {
                yaml.load(this.file);
            } catch (IOException | InvalidConfigurationException e) {
                throw new IllegalStateException("can't read " + this.file + ": " + e.getMessage(), e);
            }
        } else {
            this.logger.warning(this.file + " does not exist: no message loaded from it");
        }
        Map<String, String> texts = new LinkedHashMap<>();
        collect(yaml, texts);
        Map<String, Template> compiled = this.flow.compileAll(texts);
        compiled.forEach((key, template) -> {
            String reason = template.fallbackReason();
            if (reason != null) {
                this.logger.log(Level.FINE, () -> "message '" + key + "' renders through MiniMessage: " + reason);
            }
        });
        this.templates = compiled;
        this.warnedMissing.clear();
    }

    /**
     * Every message of a section and its subsections, by full key ({@code shop.bought}).
     */
    private static void collect(@NotNull ConfigurationSection section, @NotNull Map<String, String> texts) {
        for (String key : section.getKeys(true)) {
            if (section.isString(key)) {
                texts.put(key, Objects.requireNonNull(section.getString(key)));
            } else if (section.isList(key)) {
                List<String> lines = section.getStringList(key);
                texts.put(key, String.join("\n", lines));
            }
        }
    }

    /**
     * The template of a key, or null when the file has none.
     */
    public @Nullable Template template(@NotNull String key) {
        return this.templates.get(key);
    }

    /**
     * Every message, by key, as the last load gave them.
     */
    public @NotNull Map<String, Template> templates() {
        return Collections.unmodifiableMap(this.templates);
    }

    /**
     * Renders a message. A key the file does not have gives its name as text, and the plugin's logger warns once.
     */
    public @NotNull Component format(@NotNull String key, @NotNull Placeholder placeholders, @Nullable Pointered viewer) {
        Template template = this.templates.get(key);
        if (template == null) {
            if (this.warnedMissing.add(key)) {
                this.logger.warning("no message '" + key + "' in " + this.file.getName());
            }
            return Component.text(key);
        }
        return template.render(placeholders, viewer);
    }

    /**
     * Renders a message without values of its own.
     */
    public @NotNull Component format(@NotNull String key, @Nullable Pointered viewer) {
        return this.format(key, Placeholder.empty(), viewer);
    }
}
