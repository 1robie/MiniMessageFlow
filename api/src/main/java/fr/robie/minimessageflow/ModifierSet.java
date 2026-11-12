package fr.robie.minimessageflow;

import fr.robie.minimessageflow.PlaceholderModifiers.Modifier;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * The modifiers one {@code MiniMessageFlow} reads: its own, then the shared ones of {@link PlaceholderModifiers}.
 * Immutable: its own modifiers are known before any message is compiled, so {@code <name:mine>} always reads the
 * same way, and two instances can have different modifiers.
 */
public final class ModifierSet {

    private static final ModifierSet SHARED = new ModifierSet(Map.of());

    private final Map<String, Modifier> own;

    private ModifierSet(@NotNull Map<String, Modifier> own) {
        this.own = own;
    }

    /**
     * Only the shared modifiers of {@link PlaceholderModifiers}.
     */
    public static @NotNull ModifierSet shared() {
        return SHARED;
    }

    public static @NotNull Builder builder() {
        return new Builder();
    }

    /**
     * @param name the name, case-insensitive
     * @return the modifier of this set, else the shared one, else null
     */
    public @Nullable Modifier get(@NotNull String name) {
        Modifier modifier = this.own.isEmpty() ? null : this.own.get(name.toLowerCase(Locale.ROOT));
        return modifier != null ? modifier : PlaceholderModifiers.get(name);
    }

    /**
     * Whether an argument of a {@code <name:argument>} tag starts a modifier: an exact, lower-case modifier name.
     */
    public boolean isModifier(@NotNull String argument) {
        return !argument.isEmpty() && argument.equals(argument.toLowerCase(Locale.ROOT)) && this.get(argument) != null;
    }

    /**
     * As {@link PlaceholderModifiers#resolve}, with the modifiers of this set.
     */
    public @Nullable String resolve(@NotNull String key, @NotNull Function<String, String> lookup) {
        return PlaceholderModifiers.resolve(key, lookup, this::get);
    }

    /**
     * As {@link PlaceholderModifiers#apply}, with the modifiers of this set.
     */
    public @Nullable String apply(@NotNull String value, @NotNull String chain) {
        return PlaceholderModifiers.apply(value, chain, this::get);
    }

    public static final class Builder {
        private final Map<String, Modifier> own = new HashMap<>();

        private Builder() {
        }

        /**
         * Adds a modifier of this set, used before a shared one with the same name.
         *
         * @param name the name, case-insensitive, without {@code .} or {@code :}
         */
        public @NotNull Builder add(@NotNull String name, @NotNull Modifier modifier) {
            this.own.put(PlaceholderModifiers.checkName(name), Objects.requireNonNull(modifier, "modifier cannot be null"));
            return this;
        }

        public @NotNull ModifierSet build() {
            return this.own.isEmpty() ? SHARED : new ModifierSet(Map.copyOf(this.own));
        }
    }
}
