package fr.robie.minimessageflow;

import fr.robie.minimessageflow.internal.PlaceholderScanner;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.ComponentLike;
import net.kyori.adventure.pointer.Pointered;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Represents a collection of named placeholders for message formatting.
 * Immutable placeholder map that can be used to replace tokens in messages.
 *
 * <p>Placeholders can be:
 * <ul>
 *   <li><strong>Static:</strong> Fixed string values (e.g., "John")</li>
 *   <li><strong>Dynamic:</strong> Supplier-based values evaluated at resolution time (e.g., {@code () -> "value"})</li>
 *   <li><strong>Viewer-specific:</strong> Function-based values evaluated per viewer (e.g., {@code viewer -> viewer.get(Identity.NAME).orElse("")})</li>
 *   <li><strong>Components:</strong> each of these as a component, inserted with its own style: {@code of(key, component)},
 *   {@code ofComponent(key, () -> item.displayName())}, {@code ofViewerComponent(key, viewer -> ...)}</li>
 * </ul>
 *
 * <p>Null values are converted to empty strings for consistency.
 *
 * <p>Usage examples:
 * <pre>
 * // Static placeholder
 * Placeholder p1 = Placeholder.of("name", "John");
 *
 * // Dynamic placeholder with supplier
 * Placeholder p2 = Placeholder.of("time", () -> System.currentTimeMillis());
 *
 * // Viewer-specific placeholder with function
 * Placeholder p3 = Placeholder.of("name", viewer -> viewer.get(Identity.NAME).orElse(""));
 *
 * // Multiple placeholders using builder
 * Placeholder p4 = Placeholder.builder()
 *     .register("name", "John")
 *     .register("level", "10")
 *     .register("viewer", viewer -> viewer.get(Identity.NAME).orElse(""))
 *     .build();
 *
 * // Empty placeholders (no replacements)
 * Placeholder p5 = Placeholder.empty();
 * </pre>
 */
public final class Placeholder {
    private static final Placeholder EMPTY = new Placeholder(Collections.emptyMap());

    private final Map<String, PlaceholderValue> placeholders;
    private final boolean hasViewerValues;

    /**
     * Creates a new Placeholder with the given key-value pairs.
     *
     * @param placeholders an immutable map of placeholders
     */
    private Placeholder(@NotNull Map<String, PlaceholderValue> placeholders) {
        this.placeholders = placeholders;
        this.hasViewerValues = placeholders.values().stream().anyMatch(PlaceholderValue::needsViewer);
    }

    /**
     * Gets the immutable map of placeholders.
     *
     * @return an immutable map of placeholder key-value pairs
     */
    public @NotNull Map<String, PlaceholderValue> getMap() {
        return this.placeholders;
    }

    /**
     * Checks if there are no placeholders defined.
     *
     * @return true if there are no placeholders, false otherwise
     */
    public boolean isEmpty() {
        return this.placeholders.isEmpty();
    }

    /**
     * Returns the value registered for a key.
     *
     * @param key the placeholder key, without the surrounding {@code %}
     * @return the value, or null if none is registered
     */
    public @Nullable PlaceholderValue get(@NotNull String key) {
        return this.placeholders.get(key);
    }


    @Contract("null -> null; !null -> _")
    public String parse(@Nullable String message) {
        return this.parse(message, null);
    }

    /**
     * Parses the message by replacing placeholders with their evaluated values.
     *
     * @param message the message to parse (may be null)
     * @param viewer  the viewer for viewer-specific placeholders (may be null)
     * @return the parsed message with replacements, or null if input is null
     */
    @Contract("null, _ -> null; !null, _ -> _")
    public String parse(@Nullable String message, @Nullable Pointered viewer) {
        if (message == null) {
            return null;
        }
        if (this.isEmpty() || message.indexOf('%') < 0) {
            return message;
        }

        // Only the keys found in the message are evaluated, each once.
        Map<String, String> evaluated = new HashMap<>(4);
        Function<String, String> lookup = key -> {
            PlaceholderValue value = this.placeholders.get(key);
            if (value == null) {
                return null;
            }
            return evaluated.computeIfAbsent(key, k -> {
                try {
                    return value.evaluate(viewer);
                } catch (Exception e) {
                    return null;
                }
            });
        };
        return PlaceholderScanner.replace(message, (key, start) -> PlaceholderModifiers.resolve(key, lookup));
    }

    /**
     * Tells whether a text uses a viewer-specific placeholder of this set.
     *
     * @param text the text
     * @return true when the text needs a viewer to be formatted
     */
    public boolean isViewerDependent(@Nullable String text) {
        return this.hasViewerValues && text != null
                && PlaceholderScanner.anyKey(text, key -> {
            String base = PlaceholderModifiers.baseKey(key, this.placeholders::containsKey);
            return base != null && this.placeholders.get(base).needsViewer();
        });
    }


    /**
     * Gets an empty Placeholder (no replacements).
     * This is a singleton-like instance that can be safely reused.
     *
     * @return an empty Placeholder
     */
    public static @NotNull Placeholder empty() {
        return EMPTY;
    }

    /**
     * Creates a Placeholder with a single static key-value pair.
     *
     * @param key   the placeholder key (must not be null)
     * @param value the placeholder value (null values become empty strings)
     * @return a Placeholder containing the single key-value pair
     */
    public static @NotNull Placeholder of(@NotNull String key, String value) {
        return new Placeholder(Map.of(key, PlaceholderValue.ofStatic(value == null ? "" : value)));
    }

    /**
     * Creates a Placeholder with a single dynamic key-value pair using a supplier.
     *
     * @param key      the placeholder key (must not be null)
     * @param supplier the supplier that provides the placeholder value
     * @return a Placeholder containing the single key-value pair
     */
    public static @NotNull Placeholder of(@NotNull String key, @NotNull Supplier<String> supplier) {
        return new Placeholder(Map.of(key, PlaceholderValue.ofDynamic(supplier)));
    }

    /**
     * Creates a Placeholder with a single viewer-specific key-value pair using a function.
     *
     * @param key      the placeholder key (must not be null)
     * @param function the function that provides the placeholder value from the viewer
     * @return a Placeholder containing the single key-value pair
     */
    public static @NotNull Placeholder of(@NotNull String key, @NotNull Function<Pointered, String> function) {
        return new Placeholder(Map.of(key, PlaceholderValue.ofViewer(function)));
    }

    /**
     * Creates a placeholder whose value is a component, inserted with its own style (Adventure only).
     *
     * @param key       the placeholder key
     * @param component the component to insert
     * @return a new Placeholder instance
     */
    public static @NotNull Placeholder of(@NotNull String key, @NotNull Component component) {
        return new Placeholder(Map.of(key, PlaceholderValue.ofComponent(component)));
    }

    /**
     * Creates a placeholder whose value is a component computed at each render, inserted with its own style.
     * Not an overload of {@code of}: a lambda could not tell a supplier of text from a supplier of components.
     *
     * @param key      the placeholder key
     * @param supplier gives the component (a null result is an empty component)
     * @return a new Placeholder instance
     */
    public static @NotNull Placeholder ofComponent(@NotNull String key, @NotNull Supplier<? extends ComponentLike> supplier) {
        return new Placeholder(Map.of(key, PlaceholderValue.ofDynamicComponent(supplier)));
    }

    /**
     * Creates a placeholder whose value is a component computed for the viewer, inserted with its own style.
     *
     * @param key      the placeholder key
     * @param function gives the component from the viewer (a null result is an empty component)
     * @return a new Placeholder instance
     */
    public static @NotNull Placeholder ofViewerComponent(@NotNull String key, @NotNull Function<Pointered, ? extends ComponentLike> function) {
        return new Placeholder(Map.of(key, PlaceholderValue.ofViewerComponent(function)));
    }

    /**
     * Creates a Placeholder with multiple static key-value pairs.
     * Arguments must be provided in key-value order.
     *
     * @param key1   the first placeholder key (must not be null)
     * @param value1 the first placeholder value (null values become empty strings)
     * @param key2   the second placeholder key (must not be null)
     * @param value2 the second placeholder value (null values become empty strings)
     * @return a Placeholder containing the provided key-value pairs
     */
    public static @NotNull Placeholder of(@NotNull String key1, String value1,
                                          @NotNull String key2, String value2) {
        Map<String, PlaceholderValue> map = new HashMap<>();
        map.put(key1, PlaceholderValue.ofStatic(value1 == null ? "" : value1));
        map.put(key2, PlaceholderValue.ofStatic(value2 == null ? "" : value2));
        return new Placeholder(Map.copyOf(map));
    }

    /**
     * Creates a Placeholder from a map of static values, for example read from a configuration.
     *
     * @param values the keys and their values (null values become empty strings)
     * @return a Placeholder containing every pair
     */
    public static @NotNull Placeholder of(@NotNull Map<String, String> values) {
        if (values.isEmpty()) {
            return EMPTY;
        }
        return builder().registerAll(values).build();
    }

    /**
     * Creates a builder for constructing Placeholder instances with multiple key-value pairs.
     *
     * @return a new Builder instance
     */
    public static @NotNull Builder builder() {
        return new Builder();
    }

    /**
     * Builder for creating Placeholder instances with multiple key-value pairs.
     * Supports static, dynamic, and viewer-specific values.
     * Useful for complex cases with many placeholders.
     */
    @SuppressWarnings("UnusedReturnValue")
    public static final class Builder {
        private final Map<String, PlaceholderValue> map = new HashMap<>();

        /**
         * Adds a static key-value pair to the builder.
         *
         * @param key   the placeholder key (must not be null)
         * @param value the placeholder value (null values become empty strings)
         * @return this Builder instance for method chaining
         */
        public @NotNull Builder register(@NotNull String key, String value) {
            this.map.put(key, PlaceholderValue.ofStatic(value == null ? "" : value));
            return this;
        }

        /**
         * Adds a dynamic key-value pair to the builder using a supplier.
         *
         * @param key      the placeholder key (must not be null)
         * @param supplier the supplier that provides the placeholder value
         * @return this Builder instance for method chaining
         */
        public @NotNull Builder register(@NotNull String key, @NotNull Supplier<String> supplier) {
            this.map.put(key, PlaceholderValue.ofDynamic(supplier));
            return this;
        }

        /**
         * Adds a viewer-specific key-value pair to the builder using a function.
         *
         * @param key      the placeholder key (must not be null)
         * @param function the function that provides the placeholder value from the viewer
         * @return this Builder instance for method chaining
         */
        public @NotNull Builder register(@NotNull String key, @NotNull Function<Pointered, String> function) {
            this.map.put(key, PlaceholderValue.ofViewer(function));
            return this;
        }

        /**
         * Registers a component placeholder, inserted with its own style (Adventure only).
         *
         * @param key       the placeholder key
         * @param component the component to insert
         * @return this builder
         */
        public @NotNull Builder register(@NotNull String key, @NotNull Component component) {
            this.map.put(key, PlaceholderValue.ofComponent(component));
            return this;
        }

        /**
         * Registers a component computed at each render, inserted with its own style.
         *
         * @param key      the placeholder key
         * @param supplier gives the component (a null result is an empty component)
         * @return this builder
         */
        public @NotNull Builder registerComponent(@NotNull String key, @NotNull Supplier<? extends ComponentLike> supplier) {
            this.map.put(key, PlaceholderValue.ofDynamicComponent(supplier));
            return this;
        }

        /**
         * Registers a component computed for the viewer, inserted with its own style.
         *
         * @param key      the placeholder key
         * @param function gives the component from the viewer (a null result is an empty component)
         * @return this builder
         */
        public @NotNull Builder registerViewerComponent(@NotNull String key, @NotNull Function<Pointered, ? extends ComponentLike> function) {
            this.map.put(key, PlaceholderValue.ofViewerComponent(function));
            return this;
        }

        /**
         * Registers every placeholder of another set, replacing values with the same key.
         *
         * @param placeholders the placeholders to copy
         * @return this builder
         */
        public @NotNull Builder registerAll(@NotNull Placeholder placeholders) {
            this.map.putAll(placeholders.placeholders);
            return this;
        }

        /**
         * Registers every pair of a map as a static value, replacing values with the same key.
         *
         * @param values the keys and their values (null values become empty strings)
         * @return this builder
         */
        public @NotNull Builder registerAll(@NotNull Map<String, String> values) {
            values.forEach(this::register);
            return this;
        }

        /**
         * Builds an immutable Placeholder from the accumulated key-value pairs.
         *
         * @return a new Placeholder instance
         */
        public @NotNull Placeholder build() {
            return new Placeholder(Map.copyOf(this.map));
        }
    }
}
