package fr.robie.minimessageflow;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.ComponentLike;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;

/**
 * The values of a render, set by {@link ValueKey} and kept in an array: made once
 * ({@code MiniMessageFlow.newValues()}) and used again for each send, where a {@link Placeholder} is built each time.
 * <pre>
 * Values values = text.newValues();
 * for (Player player : players) {
 *     values.set(PLAYER, player.getName()).set(COINS, coins(player));
 *     player.sendMessage(shop.render(values, player));
 * }
 * </pre>
 * It works with every template of the instance that made it. A key that is not set is a placeholder without a
 * value from the call: the instance's resolvers are asked, as for a key missing from a {@link Placeholder}.
 * <p>
 * <strong>Mutable and not thread-safe</strong>: one per thread, or per task that renders. A value stays set until it
 * is set again, {@link #unset} or {@link #clear}ed: when the next send may lack a value the last one had, clear
 * first. Text is plain text, never read as MiniMessage or legacy codes; a component is inserted with its own style.
 */
public final class Values {

    private final ValueKeys keys;
    /**
     * By key index: a {@code String}, a {@code Component}, or null when unset.
     */
    private final Object[] values;

    Values(@NotNull ValueKeys keys) {
        this.keys = keys;
        this.values = new Object[keys.size()];
    }

    /**
     * Sets a text value. Null is the empty text, as in {@link Placeholder}.
     */
    public @NotNull Values set(@NotNull ValueKey key, @Nullable String value) {
        this.values[this.index(key)] = value == null ? "" : value;
        return this;
    }

    /**
     * Sets a whole number, as its digits ({@code 1250}; {@code %coins.comma%} groups them).
     */
    public @NotNull Values set(@NotNull ValueKey key, long value) {
        this.values[this.index(key)] = Long.toString(value);
        return this;
    }

    /**
     * Sets a component value, inserted with its own style. Null is the empty component.
     */
    public @NotNull Values set(@NotNull ValueKey key, @Nullable ComponentLike value) {
        this.values[this.index(key)] = value == null ? Component.empty() : value.asComponent();
        return this;
    }

    /**
     * Removes a value: the placeholder is then one the call gives no value for.
     */
    public @NotNull Values unset(@NotNull ValueKey key) {
        this.values[this.index(key)] = null;
        return this;
    }

    /**
     * Removes every value.
     */
    public @NotNull Values clear() {
        Arrays.fill(this.values, null);
        return this;
    }

    public boolean isSet(@NotNull ValueKey key) {
        return this.values[this.index(key)] != null;
    }

    /**
     * The keys these values are for.
     */
    public @NotNull ValueKeys keys() {
        return this.keys;
    }

    /**
     * The value at a key's place: a {@code String}, a {@code Component}, or null when unset. For the engine.
     */
    @ApiStatus.Internal
    public @Nullable Object raw(int index) {
        return this.values[index];
    }

    /**
     * These values as a {@link Placeholder}, a copy: for a {@code Template} that does not read {@link Values}.
     */
    public @NotNull Placeholder toPlaceholder() {
        Placeholder.Builder builder = Placeholder.builder();
        for (int i = 0; i < this.values.length; i++) {
            Object value = this.values[i];
            if (value != null) {
                String name = this.keys.nameAt(i);
                if (value instanceof String text) {
                    builder.register(name, text);
                } else {
                    builder.register(name, (Component) value);
                }
            }
        }
        return builder.build();
    }

    private int index(@NotNull ValueKey key) {
        if (!this.keys.owns(key)) {
            throw new IllegalArgumentException(key + " was declared on another MiniMessageFlow");
        }
        return key.index();
    }
}
