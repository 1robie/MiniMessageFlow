package fr.robie.minimessageflow;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The keys one {@code MiniMessageFlow} declares for {@link Values}: each has a place in an array, found once when a
 * message is compiled, so a render reads a value without looking its name up. Immutable.
 * <p>
 * Made by the builder of {@code MiniMessageFlow} ({@code key(name)}); rarely used directly.
 */
public final class ValueKeys {

    private static final ValueKeys NONE = new ValueKeys(Map.of(), new Object());

    private final Map<String, ValueKey> byName;
    /**
     * What tells these keys from another instance's.
     */
    private final Object owner;

    private final String[] names;

    private ValueKeys(@NotNull Map<String, ValueKey> byName, @NotNull Object owner) {
        this.byName = byName;
        this.owner = owner;
        this.names = new String[byName.size()];
        for (ValueKey key : byName.values()) {
            this.names[key.index()] = key.name();
        }
    }

    @NotNull String nameAt(int index) {
        return this.names[index];
    }

    /**
     * No key declared.
     */
    public static @NotNull ValueKeys none() {
        return NONE;
    }

    public static @NotNull Builder builder() {
        return new Builder();
    }

    public int size() {
        return this.byName.size();
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
     * New values for these keys, all unset; a {@code double} is written in the {@link NumberStyle#AUTO} style.
     */
    public @NotNull Values newValues() {
        return new Values(this, NumberStyle.AUTO);
    }

    /**
     * New values for these keys, all unset; a {@code double} ({@link Values#set(ValueKey, double)}) is written in a
     * number style, the one the instance's number modifiers read.
     */
    public @NotNull Values newValues(@NotNull NumberStyle numberStyle) {
        return new Values(this, Objects.requireNonNull(numberStyle, "numberStyle cannot be null"));
    }

    boolean owns(@NotNull ValueKey key) {
        return key.owner() == this.owner;
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
         *                                  {@code % < > & \u00a7 \ ' " #}
         */
        public @NotNull ValueKey key(@NotNull String name) {
            Objects.requireNonNull(name, "name cannot be null");
            if (this.built != null) {
                throw new IllegalStateException("keys are declared before build()");
            }
            if (name.isEmpty()) {
                throw new IllegalArgumentException("name cannot be empty");
            }
            if (name.indexOf('%') >= 0 || !fr.robie.minimessageflow.internal.PlaceholderScanner.isKey(name)) {
                throw new IllegalArgumentException("no placeholder can be named '" + name
                        + "': no whitespace and none of % < > & \u00a7 \\ ' \" #");
            }
            ValueKey key = this.byName.get(name);
            if (key == null) {
                key = new ValueKey(name, this.byName.size(), this.owner);
                this.byName.put(name, key);
            }
            return key;
        }

        public @NotNull ValueKeys build() {
            if (this.built == null) {
                this.built = this.byName.isEmpty() ? NONE : new ValueKeys(Map.copyOf(this.byName), this.owner);
            }
            return this.built;
        }
    }
}
