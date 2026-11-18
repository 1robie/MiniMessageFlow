package fr.robie.minimessageflow;

import fr.robie.minimessageflow.internal.PlaceholderScanner;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.ComponentLike;
import net.kyori.adventure.pointer.Pointered;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

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
 * Placeholder p2 = Placeholder.of("time", () -> String.valueOf(System.currentTimeMillis()));
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
    private static final Object[] NO_ENTRIES = new Object[0];
    private static final net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer PLAIN =
            net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText();
    /**
     * Up to this many values are kept in one array, read in order: cheaper to build and to read than a map for the
     * few values a message has. More go in a map.
     */
    private static final int SMALL = 8;
    private static final Placeholder EMPTY = new Placeholder(NO_ENTRIES, 0, null, false);

    /**
     * Each key ({@code String}) followed by its value, {@code size} pairs, when there are at most {@link #SMALL}; null
     * with a map. A text value is kept as its {@code String}, wrapped only when {@link #get} is asked for it: the
     * engine reads it as it is ({@link #raw}). Any other value is its {@code PlaceholderValue}.
     */
    private final Object @Nullable [] entries;
    private final int size;
    /**
     * The values by key, when there are more than {@link #SMALL}; otherwise made when {@link #getMap()} asks for it.
     */
    private @Nullable Map<String, PlaceholderValue> map;
    private final boolean hasViewerValues;

    /**
     * @param hasViewerValues whether a value may need the viewer (true when unsure: it only spares a scan)
     */
    private Placeholder(Object @Nullable [] entries, int size, @Nullable Map<String, PlaceholderValue> map,
                        boolean hasViewerValues) {
        this.entries = entries;
        this.size = size;
        this.map = map;
        this.hasViewerValues = hasViewerValues;
    }

    private static @NotNull Placeholder one(@NotNull String key, @NotNull Object value, boolean viewer) {
        return new Placeholder(new Object[]{java.util.Objects.requireNonNull(key, "key cannot be null"), value}, 1, null, viewer);
    }

    /**
     * Gets the immutable map of placeholders.
     *
     * @return an immutable map of placeholder key-value pairs
     */
    public @NotNull Map<String, PlaceholderValue> getMap() {
        Map<String, PlaceholderValue> map = this.map;
        if (map == null) {
            // Made once, on demand: an immutable map, safe to publish without a lock.
            Map<String, PlaceholderValue> built = new HashMap<>(this.size * 2);
            for (int i = 0; i < this.size; i++) {
                built.put((String) this.entries[2 * i], wrap(this.entries[2 * i + 1]));
            }
            map = Map.copyOf(built);
            this.map = map;
        }
        return map;
    }

    /**
     * Checks if there are no placeholders defined.
     *
     * @return true if there are no placeholders, false otherwise
     */
    public boolean isEmpty() {
        return this.entries != null ? this.size == 0 : this.map.isEmpty();
    }

    /**
     * Returns the value registered for a key.
     *
     * @param key the placeholder key, without the surrounding {@code %}
     * @return the value, or null if none is registered
     */
    public @Nullable PlaceholderValue get(@NotNull String key) {
        Object value = this.raw(key);
        return value == null ? null : wrap(value);
    }

    /**
     * The value registered for a key as it is kept: a {@code String} for a text value, else its
     * {@code PlaceholderValue}; null if none is registered. For the engine, which reads a text without wrapping it.
     */
    @ApiStatus.Internal
    public @Nullable Object raw(@NotNull String key) {
        Object[] entries = this.entries;
        if (entries == null) {
            return this.map.get(key);
        }
        for (int i = 0, end = 2 * this.size; i < end; i += 2) {
            Object k = entries[i];
            if (k == key || k.equals(key)) {
                return entries[i + 1];
            }
        }
        return null;
    }

    private static @NotNull PlaceholderValue wrap(@NotNull Object value) {
        return value instanceof String text ? PlaceholderValue.ofStatic(text) : (PlaceholderValue) value;
    }

    /**
     * Calls the action with each key and its value.
     */
    private void forEach(@NotNull java.util.function.BiConsumer<String, Object> action) {
        if (this.entries == null) {
            this.map.forEach(action);
            return;
        }
        for (int i = 0; i < this.size; i++) {
            action.accept((String) this.entries[2 * i], this.entries[2 * i + 1]);
        }
    }


    /**
     * Replaces the {@code %key%} placeholders of a message with their values as text, with the shared modifiers of
     * {@link PlaceholderModifiers} in the {@link NumberStyle#AUTO} style. To read modifiers as a
     * {@code MiniMessageFlow} reads them (its own, its number style), use {@link #parse(String, Pointered, ModifierSet)}.
     *
     * @param message the message to parse (may be null)
     * @return the parsed message with replacements, or null if input is null
     */
    @Contract("null -> null; !null -> _")
    public String parse(@Nullable String message) {
        return this.parse(message, null, ModifierSet.shared());
    }

    /**
     * Replaces the {@code %key%} placeholders of a message with their values as text, with the shared modifiers of
     * {@link PlaceholderModifiers} in the {@link NumberStyle#AUTO} style.
     *
     * @param message the message to parse (may be null)
     * @param viewer  the viewer for viewer-specific placeholders (may be null)
     * @return the parsed message with replacements, or null if input is null
     * @see #parse(String, Pointered, ModifierSet)
     */
    @Contract("null, _ -> null; !null, _ -> _")
    public String parse(@Nullable String message, @Nullable Pointered viewer) {
        return this.parse(message, viewer, ModifierSet.shared());
    }

    /**
     * Replaces the {@code %key%} placeholders of a message with their values as text, reading modifiers with a
     * {@link ModifierSet}: with the one a {@code MiniMessageFlow} was built with, modifiers give the same text as in
     * its renders.
     *
     * @param message   the message to parse (may be null)
     * @param viewer    the viewer for viewer-specific placeholders (may be null)
     * @param modifiers the modifiers, and the number style of the number modifiers
     * @return the parsed message with replacements, or null if input is null
     */
    @Contract("null, _, _ -> null; !null, _, _ -> _")
    public String parse(@Nullable String message, @Nullable Pointered viewer, @NotNull ModifierSet modifiers) {
        java.util.Objects.requireNonNull(modifiers, "modifiers cannot be null");
        if (message == null) {
            return null;
        }
        if (this.isEmpty() || message.indexOf('%') < 0) {
            return message;
        }

        // Only the keys found in the message are evaluated, each once: a value that fails is not asked again. A
        // component gives its legacy text as the token's whole value (its colours kept), its plain text to a modifier
        // (as in a render: %item.length% counts what is shown, not the colour codes), kept apart.
        Map<String, String> evaluated = new HashMap<>(4);
        Map<String, String> plainTexts = new HashMap<>(2);
        return PlaceholderScanner.replace(message, (token, start) -> modifiers.resolve(token, key -> {
            Object raw = this.raw(key);
            if (raw == null) {
                return null;
            }
            if (raw instanceof String text) {
                return text;
            }
            PlaceholderValue value = (PlaceholderValue) raw;
            boolean plain = value.isComponent() && !key.equals(token);
            Map<String, String> texts = plain ? plainTexts : evaluated;
            if (texts.containsKey(key)) {
                return texts.get(key);
            }
            String text;
            try {
                text = plain ? PLAIN.serialize(value.asComponent(viewer)) : value.evaluate(viewer);
            } catch (Exception e) {
                text = null; // the token is kept as written
            }
            texts.put(key, text);
            return text;
        }));
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
            String base = PlaceholderModifiers.baseKey(key, k -> this.raw(k) != null);
            // A text value (a String) never needs the viewer: read as it is kept, nothing is wrapped.
            return base != null && this.raw(base) instanceof PlaceholderValue value && value.needsViewer();
        });
    }


    /**
     * The keys and the kind of each value, never the values themselves (a log does not show a player's data):
     * {@code Placeholder{name=text, time=dynamic, rank=viewer, item=component}}.
     */
    @Override
    public String toString() {
        StringBuilder out = new StringBuilder("Placeholder{");
        int start = out.length();
        this.forEach((key, value) -> {
            if (out.length() > start) {
                out.append(", ");
            }
            out.append(key).append('=').append(kind(value));
        });
        return out.append('}').toString();
    }

    private static @NotNull String kind(@NotNull Object value) {
        if (value instanceof String) {
            return "text";
        }
        return switch ((PlaceholderValue) value) {
            case PlaceholderValue.StaticValue ignored -> "text";
            case PlaceholderValue.DynamicValue ignored -> "dynamic";
            case PlaceholderValue.ViewerValue ignored -> "viewer";
            case PlaceholderValue.ComponentValue ignored -> "component";
            case PlaceholderValue.DynamicComponentValue ignored -> "dynamic component";
            case PlaceholderValue.ViewerComponentValue ignored -> "viewer component";
            case PlaceholderValue.FormattedValue ignored -> "formatted";
        };
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
        return one(key, value == null ? "" : value, false);
    }

    /**
     * Creates a Placeholder with a single dynamic key-value pair using a supplier.
     *
     * @param key      the placeholder key (must not be null)
     * @param supplier the supplier that provides the placeholder value
     * @return a Placeholder containing the single key-value pair
     */
    public static @NotNull Placeholder of(@NotNull String key, @NotNull Supplier<String> supplier) {
        return one(key, PlaceholderValue.ofDynamic(supplier), false);
    }

    /**
     * Creates a Placeholder with a single viewer-specific key-value pair using a function.
     *
     * @param key      the placeholder key (must not be null)
     * @param function the function that provides the placeholder value from the viewer
     * @return a Placeholder containing the single key-value pair
     */
    public static @NotNull Placeholder of(@NotNull String key, @NotNull Function<Pointered, String> function) {
        return one(key, PlaceholderValue.ofViewer(function), true);
    }

    /**
     * Creates a placeholder whose value is a component, inserted with its own style (Adventure only).
     *
     * @param key       the placeholder key
     * @param component the component to insert
     * @return a new Placeholder instance
     */
    public static @NotNull Placeholder of(@NotNull String key, @NotNull Component component) {
        return one(key, PlaceholderValue.ofComponent(component), false);
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
        return one(key, PlaceholderValue.ofDynamicComponent(supplier), false);
    }

    /**
     * Creates a placeholder whose value is a component computed for the viewer, inserted with its own style.
     *
     * @param key      the placeholder key
     * @param function gives the component from the viewer (a null result is an empty component)
     * @return a new Placeholder instance
     */
    public static @NotNull Placeholder ofViewerComponent(@NotNull String key, @NotNull Function<Pointered, ? extends ComponentLike> function) {
        return one(key, PlaceholderValue.ofViewerComponent(function), true);
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
        java.util.Objects.requireNonNull(key1, "key cannot be null");
        java.util.Objects.requireNonNull(key2, "key cannot be null");
        String second = value2 == null ? "" : value2;
        if (key1.equals(key2)) {
            return one(key2, second, false); // the last one wins, as in a builder
        }
        return new Placeholder(new Object[]{key1, value1 == null ? "" : value1, key2, second}, 2, null, false);
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
        /**
         * Each key followed by its value, {@link #size} pairs, while there are at most {@link #SMALL}; then
         * {@link #map}.
         */
        private Object[] entries = NO_ENTRIES;
        private int size;
        private @Nullable Map<String, PlaceholderValue> map;
        /**
         * Whether a value that needs the viewer was ever registered.
         */
        private boolean viewerValues;
        /**
         * Whether the array belongs to a built Placeholder: it is copied before the next change. (A built Placeholder
         * gets its own copy of the map.)
         */
        private boolean built;

        /**
         * Sets a key's value, replacing the one it had.
         *
         * @param value a {@code String} (text) or a {@code PlaceholderValue}
         */
        private void put(@NotNull String key, @NotNull Object value) {
            java.util.Objects.requireNonNull(key, "key cannot be null");
            if (this.built) {
                this.entries = this.entries.clone();
                this.built = false;
            }
            if (this.map != null) {
                this.map.put(key, wrap(value));
                return;
            }
            for (int i = 0, end = 2 * this.size; i < end; i += 2) {
                if (this.entries[i].equals(key)) {
                    this.entries[i + 1] = value;
                    return;
                }
            }
            if (this.size == SMALL) {
                Map<String, PlaceholderValue> map = new HashMap<>(SMALL * 4);
                for (int i = 0; i < this.size; i++) {
                    map.put((String) this.entries[2 * i], wrap(this.entries[2 * i + 1]));
                }
                map.put(key, wrap(value));
                this.map = map;
                return;
            }
            if (2 * this.size == this.entries.length) {
                this.entries = java.util.Arrays.copyOf(this.entries, this.size == 0 ? 8 : 2 * SMALL);
            }
            this.entries[2 * this.size] = key;
            this.entries[2 * this.size + 1] = value;
            this.size++;
        }

        /**
         * Adds a static key-value pair to the builder.
         *
         * @param key   the placeholder key (must not be null)
         * @param value the placeholder value (null values become empty strings)
         * @return this Builder instance for method chaining
         */
        public @NotNull Builder register(@NotNull String key, String value) {
            this.put(key, value == null ? "" : value);
            return this;
        }

        /**
         * Adds formatted text: its tags and colour codes are read, as a resolver's text (its colours end with it).
         * Use it for text from a configuration; text a player typed goes through {@link #register(String, String)},
         * which is never read as tags.
         *
         * @param key  the placeholder key (must not be null)
         * @param text the formatted text (null is the empty text)
         * @return this Builder instance for method chaining
         */
        public @NotNull Builder registerFormatted(@NotNull String key, @Nullable String text) {
            this.put(key, PlaceholderValue.ofFormatted(text == null ? "" : text));
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
            this.put(key, PlaceholderValue.ofDynamic(supplier));
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
            this.put(key, PlaceholderValue.ofViewer(function));
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
            this.put(key, PlaceholderValue.ofComponent(component));
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
            this.put(key, PlaceholderValue.ofDynamicComponent(supplier));
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
            this.put(key, PlaceholderValue.ofViewerComponent(function));
            this.viewerValues = true;
            return this;
        }

        /**
         * Registers a value of any kind ({@code PlaceholderValue.ofDynamicComponent(...)}...).
         *
         * @param key   the placeholder key
         * @param value the value
         * @return this builder
         */
        public @NotNull Builder register(@NotNull String key, @NotNull PlaceholderValue value) {
            this.put(key, java.util.Objects.requireNonNull(value, "value cannot be null"));
            this.viewerValues |= value.needsViewer();
            return this;
        }

        /**
         * Registers every placeholder of another set, replacing values with the same key.
         *
         * @param placeholders the placeholders to copy
         * @return this builder
         */
        public @NotNull Builder registerAll(@NotNull Placeholder placeholders) {
            placeholders.forEach(this::put);
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
            if (this.size == 0 && this.map == null) {
                return EMPTY;
            }
            if (this.map != null) {
                // An immutable copy: its fields are final, so a Placeholder handed to another thread without a lock
                // still reads every value (the field that holds it is not final: it is also the lazy getMap()).
                return new Placeholder(null, 0, Map.copyOf(this.map), this.viewerValues);
            }
            // The builder's own array, read-only from now on: the builder copies it if it is used again. The field
            // that holds it is final.
            this.built = true;
            return new Placeholder(this.entries, this.size, null, this.viewerValues);
        }
    }
}
