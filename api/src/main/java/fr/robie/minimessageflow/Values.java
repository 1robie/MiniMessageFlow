package fr.robie.minimessageflow;

import net.kyori.adventure.pointer.Pointered;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.ComponentLike;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

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
     * By key index: a {@code String}, a {@code Component}, a {@link PlaceholderValue} (computed at each render), or
     * null when unset.
     */
    private final Object[] values;
    /**
     * How {@link #set(ValueKey, double)} writes a decimal: the style the instance's number modifiers read.
     */
    private final NumberStyle numberStyle;

    Values(@NotNull ValueKeys keys, @NotNull NumberStyle numberStyle) {
        this.keys = keys;
        this.values = new Object[keys.size()];
        this.numberStyle = numberStyle;
    }

    /**
     * Sets a text value. Null is the empty text, as in {@link Placeholder}: write it {@code set(key, (String) null)},
     * since a bare {@code null} also fits the other {@code set} methods. To remove the value instead (the key then has
     * no value from the call, and the resolvers are asked), use {@link #unset}.
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
     * Sets a number that may have decimals, written in full without an exponent ({@code 2.5}, {@code 0.000001},
     * {@code 1000}) with the decimal point of the instance's {@link NumberStyle} ({@code 2,5} in
     * {@link NumberStyle#EUROPEAN}), so its number modifiers read it: {@code %price.2f%}, {@code %price.comma%}.
     *
     * @throws IllegalArgumentException for {@code NaN} or an infinity, which no number modifier reads
     */
    public @NotNull Values set(@NotNull ValueKey key, double value) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("not a number: " + value);
        }
        int index = this.index(key);
        String text = value == (long) value && Math.abs(value) < 0x1p63 ? Long.toString((long) value)
                : java.math.BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
        this.values[index] = this.numberStyle == NumberStyle.EUROPEAN ? text.replace('.', ',') : text;
        return this;
    }

    /**
     * Sets a component value, inserted with its own style. Null is the empty component ({@code set(key,
     * (ComponentLike) null)}).
     */
    public @NotNull Values set(@NotNull ValueKey key, @Nullable ComponentLike value) {
        this.values[this.index(key)] = value == null ? Component.empty() : value.asComponent();
        return this;
    }

    /**
     * Sets a text computed at each render, as {@code Placeholder.of(key, supplier)}: asked once per render, wherever
     * the placeholder is written. The supplier stays set until the key is set again. Not an overload of
     * {@code set}: a lambda could not tell a supplier of text from a component.
     */
    public @NotNull Values setComputed(@NotNull ValueKey key, @NotNull Supplier<String> supplier) {
        return this.set(key, PlaceholderValue.ofDynamic(Objects.requireNonNull(supplier, "supplier cannot be null")));
    }

    /**
     * Sets a text computed for the viewer of each render, as {@code Placeholder.of(key, function)}.
     */
    public @NotNull Values setForViewer(@NotNull ValueKey key, @NotNull Function<Pointered, String> function) {
        return this.set(key, PlaceholderValue.ofViewer(Objects.requireNonNull(function, "function cannot be null")));
    }

    /**
     * Sets any value a {@link Placeholder} can hold, for the kinds without a method of their own here (a component
     * computed at each render or for the viewer: {@code PlaceholderValue.ofDynamicComponent(...)}).
     */
    public @NotNull Values set(@NotNull ValueKey key, @NotNull PlaceholderValue value) {
        this.values[this.index(key)] = Objects.requireNonNull(value, "value cannot be null");
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
     * The value at a key's place: a {@code String}, a {@code Component}, a {@link PlaceholderValue}, or null when
     * unset. For the engine.
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
                } else if (value instanceof PlaceholderValue computed) {
                    builder.register(name, computed);
                } else {
                    builder.register(name, (Component) value);
                }
            }
        }
        return builder.build();
    }

    /**
     * The keys and the kind of each value set, never the values themselves (a log does not show a player's data):
     * {@code Values{player=text, item=component, time=dynamic}}. Unset keys are left out.
     */
    @Override
    public String toString() {
        StringBuilder out = new StringBuilder("Values{");
        int start = out.length();
        for (int i = 0; i < this.values.length; i++) {
            Object value = this.values[i];
            if (value == null) {
                continue;
            }
            if (out.length() > start) {
                out.append(", ");
            }
            out.append(this.keys.nameAt(i)).append('=').append(switch (value) {
                case String ignored -> "text";
                case Component ignored -> "component";
                case PlaceholderValue.StaticValue ignored -> "text";
                case PlaceholderValue.DynamicValue ignored -> "dynamic";
                case PlaceholderValue.ViewerValue ignored -> "viewer";
                case PlaceholderValue.ComponentValue ignored -> "component";
                case PlaceholderValue.DynamicComponentValue ignored -> "dynamic component";
                case PlaceholderValue.ViewerComponentValue ignored -> "viewer component";
                default -> value.getClass().getSimpleName();
            });
        }
        return out.append('}').toString();
    }

    private int index(@NotNull ValueKey key) {
        if (!this.keys.owns(key)) {
            throw new IllegalArgumentException(key + " was declared on another MiniMessageFlow");
        }
        return key.index();
    }
}
