package fr.robie.minimessageflow.paper;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The messages of a YAML file, by full key ({@code shop.bought}): a text is a message, a list of lines is one
 * message with its lines joined by new lines; sections, numbers and the rest are not messages.
 */
final class YamlMessages {

    private YamlMessages() {
    }

    /**
     * @throws IllegalStateException when the file can't be read or is not valid YAML
     */
    static @NotNull Map<String, String> read(@NotNull File file) {
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(file);
        } catch (IOException | InvalidConfigurationException e) {
            throw new IllegalStateException("can't read " + file + ": " + e.getMessage(), e);
        }
        Map<String, String> texts = new LinkedHashMap<>();
        collect(yaml, texts);
        return texts;
    }

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
}
