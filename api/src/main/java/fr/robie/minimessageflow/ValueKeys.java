package fr.robie.minimessageflow;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The keys one {@code MiniMessageFlow} declares for {@link Values}: each has a place in an array, found once when a
 * message is compiled, so a render reads a value without looking its name up.
 * <p>
 * Made by the builder of {@code MiniMessageFlow} ({@code key(name)}). Keys can be added after it is built
 * ({@code MiniMessageFlow.key(name)}): a key is never removed and keeps its place, and adding one is thread-safe. A
 * message compiled before a key was added still reads it, by its name (slower); compile after declaring for the fast
 * path.
 */
public final class ValueKeys {

    private static final java.util.function.Supplier<NumberStyle> AUTO_STYLE = () -> NumberStyle.AUTO;
    private static final ValueKeys NONE = new ValueKeys(Map.of(), new Object(), true);

    private final Map<String, ValueKey> byName;
    /**
     * What tells these keys from another instance's.
     */
    private final Object owner;
    /**
     * Whether keys can no longer be added ({@link #none()}).
     */
    private final boolean frozen;

    private volatile String[] names;

    private ValueKeys(@NotNull Map<String, ValueKey> byName, @NotNull Object owner, boolean frozen) {
        this.byName = frozen ? byName : new ConcurrentHashMap<>(byName);
        this.owner = owner;
        this.frozen = frozen;
        String[] names = new String[byName.size()];
        for (ValueKey key : byName.values()) {
            names[key.index()] = key.name();
        }
        this.names = names;
    }

    @NotNull String nameAt(int index) {
        return this.names[index];
    }

    /**
     * No key declared, and none can be added.
     */
    public static @NotNull ValueKeys none() {
        return NONE;
    }

    public static @NotNull Builder builder() {
        return new Builder();
    }

    public int size() {
        return this.names.length;
    }

    /**
     * @return the key declared with this name, or null
     */
    public @Nullable ValueKey get(@NotNull String name) {
        return this.byName.get(name);
    }

    /**
     * @return the place of the key with this name, or -1 when none was declared
     */
    public int indexOf(@NotNull String name) {
        ValueKey key = this.byName.get(name);
        return key == null ? -1 : key.index();
    }

    /**
     * Declares a key after these keys were built, or gives the one already declared with this name. Thread-safe.
     *
     * @throws IllegalArgumentException when no placeholder can have this name (see {@link Builder#key})
     * @throws IllegalStateException    on {@link #none()}
     */
    public @NotNull ValueKey add(@NotNull String name) {
        ValueKey key = this.byName.get(Objects.requireNonNull(name, "name cannot be null"));
        if (key != null) {
            return key;
        }
        if (this.frozen) {
            throw new IllegalStateException("no key can be added to ValueKeys.none()");
        }
        checkName(name);
        synchronized (this) {
            key = this.byName.get(name);
            if (key == null) {
                String[] names = this.names;
                key = new ValueKey(name, names.length, this.owner);
                String[] grown = java.util.Arrays.copyOf(names, names.length + 1);
                grown[names.length] = name;
                // The name is readable at its place before the key can be found.
                this.names = grown;
                this.byName.put(name, key);
            }
            return key;
        }
    }

    /**
     * New values for these keys, all unset; a {@code double} is written in the {@link NumberStyle#AUTO} style.
     */
    public @NotNull Values newValues() {
        return new Values(this, null, AUTO_STYLE);
    }

    /**
     * New values for these keys, all unset; a {@code double} ({@link Values#set(ValueKey, double)}) is written in a
     * number style, the one the instance's number modifiers read.
     */
    public @NotNull Values newValues(@NotNull NumberStyle numberStyle) {
        Objects.requireNonNull(numberStyle, "numberStyle cannot be null");
        return new Values(this, null, () -> numberStyle);
    }

    /**
     * New values writing decimals in the style the source gives at each {@code set}: an instance's, which can change.
     * For {@code MiniMessageFlow}.
     */
    @org.jetbrains.annotations.ApiStatus.Internal
    public @NotNull Values newValues(@NotNull java.util.function.Supplier<NumberStyle> numberStyle) {
        return new Values(this, null, Objects.requireNonNull(numberStyle, "numberStyle cannot be null"));
    }

    boolean owns(@NotNull ValueKey key) {
        return key.owner() == this.owner;
    }

    private static void checkName(@NotNull String name) {
        if (name.isEmpty()) {
            throw new IllegalArgumentException("name cannot be empty");
        }
        if (name.indexOf('%') >= 0 || !fr.robie.minimessageflow.internal.PlaceholderScanner.isKey(name)) {
            throw new IllegalArgumentException("no placeholder can be named '" + name
                    + "': no whitespace and none of % < > & § \\ ' \" #");
        }
    }

    public static final class Builder {
        private final Map<String, ValueKey> byName = new LinkedHashMap<>();
        private final Object owner = new Object();
        private @Nullable ValueKeys built;

        private Builder() {
        }

        /**
         * Declares a key, or gives the one already declared with this name.
         *
         * @param name the placeholder's name, as in {@code %name%} or {@code <name>}, without modifiers
         * @throws IllegalArgumentException when no placeholder can have this name: empty, or with whitespace or one of
         *                                  {@code % < > & § \ ' " #}
         */
        public @NotNull ValueKey key(@NotNull String name) {
            Objects.requireNonNull(name, "name cannot be null");
            if (this.built != null) {
                return this.built.add(name);
            }
            checkName(name);
            ValueKey key = this.byName.get(name);
            if (key == null) {
                key = new ValueKey(name, this.byName.size(), this.owner);
                this.byName.put(name, key);
            }
            return key;
        }

        /**
         * The keys, which can still be added to ({@link ValueKeys#add}).
         */
        public @NotNull ValueKeys build() {
            if (this.built == null) {
                this.built = new ValueKeys(this.byName, this.owner, false);
            }
            return this.built;
        }
    }
}
