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
    private static final Placeholder EMPTY = new Placeholder(Collections.emptyMap(), false);

    private final Map<String, PlaceholderValue> placeholders;
    private final boolean hasViewerValues;

    /**
     * Creates a new Placeholder with the given key-value pairs.
     *
     * @param placeholders    an immutable map of placeholders
     * @param hasViewerValues whether a value may need the viewer (true when unsure: it only spares a scan)
     */
    private Placeholder(@NotNull Map<String, PlaceholderValue> placeholders, boolean hasViewerValues) {
        this.placeholders = placeholders;
        this.hasViewerValues = hasViewerValues;
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
        return new Placeholder(Map.of(key, PlaceholderValue.ofStatic(value == null ? "" : value)), false);
    }

    /**
     * Creates a Placeholder with a single dynamic key-value pair using a supplier.
     *
     * @param key      the placeholder key (must not be null)
     * @param supplier the supplier that provides the placeholder value
     * @return a Placeholder containing the single key-value pair
     */
    public static @NotNull Placeholder of(@NotNull String key, @NotNull Supplier<String> supplier) {
        return new Placeholder(Map.of(key, PlaceholderValue.ofDynamic(supplier)), false);
    }

    /**
     * Creates a Placeholder with a single viewer-specific key-value pair using a function.
     *
     * @param key      the placeholder key (must not be null)
     * @param function the function that provides the placeholder value from the viewer
     * @return a Placeholder containing the single key-value pair
     */
    public static @NotNull Placeholder of(@NotNull String key, @NotNull Function<Pointered, String> function) {
        return new Placeholder(Map.of(key, PlaceholderValue.ofViewer(function)), true);
    }

    /**
     * Creates a placeholder whose value is a component, inserted with its own style (Adventure only).
     *
     * @param key       the placeholder key
     * @param component the component to insert
     * @return a new Placeholder instance
     */
    public static @NotNull Placeholder of(@NotNull String key, @NotNull Component component) {
        return new Placeholder(Map.of(key, PlaceholderValue.ofComponent(component)), false);
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
        return new Placeholder(Map.of(key, PlaceholderValue.ofDynamicComponent(supplier)), false);
    }

    /**
     * Creates a placeholder whose value is a component computed for the viewer, inserted with its own style.
     *
     * @param key      the placeholder key
     * @param function gives the component from the viewer (a null result is an empty component)
     * @return a new Placeholder instance
     */
    public static @NotNull Placeholder ofViewerComponent(@NotNull String key, @NotNull Function<Pointered, ? extends ComponentLike> function) {
        return new Placeholder(Map.of(key, PlaceholderValue.ofViewerComponent(function)), true);
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
        PlaceholderValue second = PlaceholderValue.ofStatic(value2 == null ? "" : value2);
        if (key1.equals(key2)) {
            return new Placeholder(Map.of(key2, second), false); // the last one wins, as in a builder
        }
        return new Placeholder(Map.of(key1, PlaceholderValue.ofStatic(value1 == null ? "" : value1), key2, second), false);
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
        private Map<String, PlaceholderValue> map = new HashMap<>();
        /**
         * Whether a value that needs the viewer was ever registered.
         */
        private boolean viewerValues;
        /**
         * Whether {@link #map} belongs to a built Placeholder: it is copied before the next change.
         */
        private boolean built;

        private @NotNull Map<String, PlaceholderValue> own() {
            if (this.built) {
                this.map = new HashMap<>(this.map);
                this.built = false;
            }
            return this.map;
        }

        /**
         * Adds a static key-value pair to the builder.
         *
         * @param key   the placeholder key (must not be null)
         * @param value the placeholder value (null values become empty strings)
         * @return this Builder instance for method chaining
         */
        public @NotNull Builder register(@NotNull String key, String value) {
            this.own().put(key, PlaceholderValue.ofStatic(value == null ? "" : value));
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
            this.own().put(key, PlaceholderValue.ofDynamic(supplier));
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
            this.own().put(key, PlaceholderValue.ofViewer(function));
            this.viewerValues = true;
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
            this.own().put(key, PlaceholderValue.ofComponent(component));
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
            this.own().put(key, PlaceholderValue.ofDynamicComponent(supplier));
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
            this.own().put(key, PlaceholderValue.ofViewerComponent(function));
            this.viewerValues = true;
            return this;
        }

        /**
         * Registers every placeholder of another set, replacing values with the same key.
         *
         * @param placeholders the placeholders to copy
         * @return this builder
         */
        public @NotNull Builder registerAll(@NotNull Placeholder placeholders) {
            this.own().putAll(placeholders.placeholders);
            this.viewerValues |= placeholders.hasViewerValues;
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
            // The builder's own map, read-only from now on: the builder copies it if it is used again.
            this.built = true;
            return new Placeholder(Collections.unmodifiableMap(this.map), this.viewerValues);
        }
    }
}
