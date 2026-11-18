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
 * <strong>Layers</strong>: {@link #child()} makes values that read their own first, then their parent's (a menu's
 * values, then each slot's). Setting a value sets it in this layer only; {@link #parent()} and {@link #root()} reach
 * the others. A lookup is one array read per layer, nothing is hashed.
 * <p>
 * <strong>Kinds</strong>: {@link #set(ValueKey, String)} is plain text, never read as MiniMessage or legacy codes
 * (what a player typed); {@link #setFormatted} is formatted text, read as a resolver's (what a configuration
 * holds); a component is inserted with its own style.
 * <p>
 * <strong>Mutable and not thread-safe</strong>: one per thread, or per task that renders, layers included. A value
 * stays set until it is set again, {@link #unset} or {@link #clear}ed: when the next send may lack a value the last
 * one had, clear first. To hand the values to another thread, take a {@link #toPlaceholder()} copy.
 */
public final class Values {

    private static final long[] NO_BITS = new long[0];

    private final ValueKeys keys;
    private final @Nullable Values parent;
    /**
     * By key index: a {@code String}, a {@code Component}, a {@link PlaceholderValue} (computed at each render), or
     * null when unset. Grows when a key added after these values were made is set.
     */
    private Object[] values;
    /**
     * By key index, whether the {@code String} there is formatted text.
     */
    private long[] formatted = NO_BITS;
    /**
     * How {@link #set(ValueKey, double)} writes a decimal: the style the instance's number modifiers read.
     */
    private final NumberStyle numberStyle;
    /**
     * The prefix lookups of this layer, in the order they were added: a key that starts with a prefix and has no
     * value is asked of its function.
     */
    private String[] lookupPrefixes = NO_PREFIXES;
    private Function<String, String>[] lookupFunctions = NO_FUNCTIONS;
    private boolean[] lookupFormatted = NO_FLAGS;

    /**
     * The last render of each template with these values, when they remember renders: by template, compared by
     * identity. Null when they don't.
     */
    private @Nullable java.util.IdentityHashMap<Object, Object> renders;

    private static final String[] NO_PREFIXES = new String[0];
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static final Function<String, String>[] NO_FUNCTIONS = new Function[0];
    private static final boolean[] NO_FLAGS = new boolean[0];

    Values(@NotNull ValueKeys keys, @Nullable Values parent, @NotNull NumberStyle numberStyle) {
        this.keys = keys;
        this.parent = parent;
        this.values = new Object[keys.size()];
        this.numberStyle = numberStyle;
    }

    /**
     * New values reading these ones when they have no value of their own for a key. Made once and kept (a slot's
     * values under its menu's), they are set again at each render without allocating.
     */
    public @NotNull Values child() {
        return new Values(this.keys, this, this.numberStyle);
    }

    /**
     * The values this layer reads after its own, or null for a root.
     */
    public @Nullable Values parent() {
        return this.parent;
    }

    /**
     * The first layer: these values when they have no parent.
     */
    public @NotNull Values root() {
        Values current = this;
        while (current.parent != null) {
            current = current.parent;
        }
        return current;
    }

    /**
     * Sets a text value, plain: shown as it is, never read as tags or colour codes. Null is the empty text, as in
     * {@link Placeholder}: write it {@code set(key, (String) null)}, since a bare {@code null} also fits the other
     * {@code set} methods. To remove the value instead (the key then has no value from the call, and the resolvers
     * are asked), use {@link #unset}.
     */
    public @NotNull Values set(@NotNull ValueKey key, @Nullable String value) {
        int index = this.index(key);
        this.values[index] = value == null ? "" : value;
        this.formatted(index, false);
        return this;
    }

    /**
     * Sets formatted text: its tags and colour codes are read, as a resolver's text, on its own (its colours end with
     * it). The same text is parsed once (the instance's value cache). With {@code %key.raw%} it is shown as written.
     * Null is the empty text.
     */
    public @NotNull Values setFormatted(@NotNull ValueKey key, @Nullable String text) {
        int index = this.index(key);
        this.values[index] = text == null ? "" : text;
        this.formatted(index, true);
        return this;
    }

    /**
     * Sets a whole number, as its digits ({@code 1250}; {@code %coins.comma%} groups them).
     */
    public @NotNull Values set(@NotNull ValueKey key, long value) {
        return this.set(key, Long.toString(value));
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
        String text = value == (long) value && Math.abs(value) < 0x1p63 ? Long.toString((long) value)
                : java.math.BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
        return this.set(key, this.numberStyle == NumberStyle.EUROPEAN ? text.replace('.', ',') : text);
    }

    /**
     * Sets a component value, inserted with its own style. Null is the empty component ({@code set(key,
     * (ComponentLike) null)}).
     */
    public @NotNull Values set(@NotNull ValueKey key, @Nullable ComponentLike value) {
        int index = this.index(key);
        this.values[index] = value == null ? Component.empty() : value.asComponent();
        this.formatted(index, false);
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
        int index = this.index(key);
        this.values[index] = Objects.requireNonNull(value, "value cannot be null");
        this.formatted(index, false);
        return this;
    }

    /**
     * Gives the values of every key that starts with a prefix and has no value in any layer, as plain text: for keys
     * nobody can declare in advance ({@code %state_<anything>%}). The function gets the rest of the key
     * ({@code count} for {@code %state_count%}), at each render, and returns null to leave the placeholder to the
     * resolvers. Modifiers apply ({@code %state_count.comma%}). Replaces this layer's lookup of the same prefix.
     */
    public @NotNull Values lookup(@NotNull String prefix, @NotNull Function<String, String> function) {
        return this.addLookup(prefix, function, false);
    }

    /**
     * As {@link #lookup}, the function's text being formatted: read as tags and colour codes.
     */
    public @NotNull Values lookupFormatted(@NotNull String prefix, @NotNull Function<String, String> function) {
        return this.addLookup(prefix, function, true);
    }

    /**
     * Removes this layer's lookup of a prefix.
     */
    public @NotNull Values removeLookup(@NotNull String prefix) {
        int at = Arrays.asList(this.lookupPrefixes).indexOf(Objects.requireNonNull(prefix, "prefix cannot be null"));
        if (at >= 0) {
            int last = this.lookupPrefixes.length - 1;
            String[] prefixes = Arrays.copyOf(this.lookupPrefixes, last);
            Function<String, String>[] functions = Arrays.copyOf(this.lookupFunctions, last);
            boolean[] formatted = Arrays.copyOf(this.lookupFormatted, last);
            System.arraycopy(this.lookupPrefixes, at + 1, prefixes, at, last - at);
            System.arraycopy(this.lookupFunctions, at + 1, functions, at, last - at);
            System.arraycopy(this.lookupFormatted, at + 1, formatted, at, last - at);
            this.lookupPrefixes = prefixes;
            this.lookupFunctions = functions;
            this.lookupFormatted = formatted;
        }
        return this;
    }

    private @NotNull Values addLookup(@NotNull String prefix, @NotNull Function<String, String> function, boolean formatted) {
        Objects.requireNonNull(prefix, "prefix cannot be null");
        Objects.requireNonNull(function, "function cannot be null");
        if (prefix.isEmpty()) {
            throw new IllegalArgumentException("prefix cannot be empty");
        }
        int at = Arrays.asList(this.lookupPrefixes).indexOf(prefix);
        if (at < 0) {
            at = this.lookupPrefixes.length;
            this.lookupPrefixes = Arrays.copyOf(this.lookupPrefixes, at + 1);
            this.lookupFunctions = Arrays.copyOf(this.lookupFunctions, at + 1);
            this.lookupFormatted = Arrays.copyOf(this.lookupFormatted, at + 1);
            this.lookupPrefixes[at] = prefix;
        }
        this.lookupFunctions[at] = function;
        this.lookupFormatted[at] = formatted;
        return this;
    }

    /**
     * Makes these values remember, for each template rendered with them, the last values and what they gave: a
     * render whose values are all the same gives the same component again (the same instance), allocating nothing.
     * Without it, each template remembers one last render shared by everyone, which values that differ from one
     * viewer to the next keep replacing. Turn it on for values kept per viewer and rendered again and again (a
     * player's open menu refreshed each second): comparing the component given with the last one ({@code ==}) then
     * tells that nothing changed. Costs one entry per template rendered with these values; {@link #forgetRenders()}
     * drops them.
     */
    public @NotNull Values rememberRenders(boolean remember) {
        this.renders = remember ? (this.renders != null ? this.renders : new java.util.IdentityHashMap<>(8)) : null;
        return this;
    }

    /**
     * Whether these values remember renders ({@link #rememberRenders}).
     */
    public boolean remembersRenders() {
        return this.renders != null;
    }

    /**
     * Drops the renders remembered, keeping the setting.
     */
    public @NotNull Values forgetRenders() {
        if (this.renders != null) {
            this.renders.clear();
        }
        return this;
    }

    /**
     * The last render of a template remembered by these values, or null. For the engine.
     */
    @ApiStatus.Internal
    public @Nullable Object lastRender(@NotNull Object template) {
        return this.renders == null ? null : this.renders.get(template);
    }

    /**
     * Remembers the last render of a template, when these values remember renders. For the engine.
     */
    @ApiStatus.Internal
    public void lastRender(@NotNull Object template, @NotNull Object render) {
        if (this.renders != null) {
            this.renders.put(template, render);
        }
    }

    /**
     * How many prefix lookups this layer has. For the engine.
     */
    @ApiStatus.Internal
    public int lookupCount() {
        return this.lookupPrefixes.length;
    }

    @ApiStatus.Internal
    public @NotNull String lookupPrefix(int index) {
        return this.lookupPrefixes[index];
    }

    @ApiStatus.Internal
    public @NotNull Function<String, String> lookupFunction(int index) {
        return this.lookupFunctions[index];
    }

    @ApiStatus.Internal
    public boolean isLookupFormatted(int index) {
        return this.lookupFormatted[index];
    }

    /**
     * Removes a value from this layer: the placeholder then reads the parent's, or has no value from the call.
     */
    public @NotNull Values unset(@NotNull ValueKey key) {
        int index = this.index(key);
        this.values[index] = null;
        this.formatted(index, false);
        return this;
    }

    /**
     * Removes every value of this layer; the parents keep theirs.
     */
    public @NotNull Values clear() {
        Arrays.fill(this.values, null);
        Arrays.fill(this.formatted, 0);
        return this;
    }

    /**
     * Whether this layer has a value for the key (a parent's is not counted).
     */
    public boolean isSet(@NotNull ValueKey key) {
        int index = this.index(key);
        return this.values[index] != null;
    }

    /**
     * The keys these values are for.
     */
    public @NotNull ValueKeys keys() {
        return this.keys;
    }

    /**
     * The value of this layer at a key's place: a {@code String}, a {@code Component}, a {@link PlaceholderValue}, or
     * null when unset. For the engine.
     */
    @ApiStatus.Internal
    public @Nullable Object raw(int index) {
        return index < this.values.length ? this.values[index] : null;
    }

    /**
     * Whether the {@code String} of this layer at a key's place is formatted text. For the engine.
     */
    @ApiStatus.Internal
    public boolean isFormatted(int index) {
        int word = index >>> 6;
        return word < this.formatted.length && (this.formatted[word] & 1L << index) != 0;
    }

    /**
     * Whether this layer ever held formatted text. For the engine: lets plain values skip the bit check.
     */
    @ApiStatus.Internal
    public boolean hasFormatted() {
        return this.formatted.length != 0;
    }

    /**
     * The first layer, from this one up, with a value at a key's place; null when none has one. For the engine.
     */
    @ApiStatus.Internal
    public @Nullable Values holder(int index) {
        for (Values layer = this; layer != null; layer = layer.parent) {
            if (index < layer.values.length && layer.values[index] != null) {
                return layer;
            }
        }
        return null;
    }

    /**
     * These values as a {@link Placeholder}, a copy, the parents' values included (this layer's first): for another
     * thread, or a {@code Template} that does not read {@link Values}.
     */
    public @NotNull Placeholder toPlaceholder() {
        Placeholder.Builder builder = Placeholder.builder();
        int size = this.keys.size();
        for (int i = 0; i < size; i++) {
            Values layer = this.holder(i);
            if (layer == null) {
                continue;
            }
            Object value = layer.values[i];
            String name = this.keys.nameAt(i);
            if (value instanceof String text) {
                if (layer.isFormatted(i)) {
                    builder.registerFormatted(name, text);
                } else {
                    builder.register(name, text);
                }
            } else if (value instanceof PlaceholderValue computed) {
                builder.register(name, computed);
            } else {
                builder.register(name, (Component) value);
            }
        }
        return builder.build();
    }

    /**
     * The keys and the kind of each value set in this layer, never the values themselves (a log does not show a
     * player's data): {@code Values{player=text, item=component, time=dynamic}}. Unset keys are left out.
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
                case String ignored -> this.isFormatted(i) ? "formatted" : "text";
                case Component ignored -> "component";
                case PlaceholderValue.StaticValue ignored -> "text";
                case PlaceholderValue.DynamicValue ignored -> "dynamic";
                case PlaceholderValue.ViewerValue ignored -> "viewer";
                case PlaceholderValue.ComponentValue ignored -> "component";
                case PlaceholderValue.DynamicComponentValue ignored -> "dynamic component";
                case PlaceholderValue.ViewerComponentValue ignored -> "viewer component";
                case PlaceholderValue.FormattedValue ignored -> "formatted";
                default -> value.getClass().getSimpleName();
            });
        }
        return out.append('}').toString();
    }

    private void formatted(int index, boolean formatted) {
        int word = index >>> 6;
        if (word >= this.formatted.length) {
            if (!formatted) {
                return;
            }
            this.formatted = Arrays.copyOf(this.formatted, word + 1);
        }
        if (formatted) {
            this.formatted[word] |= 1L << index;
        } else {
            this.formatted[word] &= ~(1L << index);
        }
    }

    private int index(@NotNull ValueKey key) {
        if (!this.keys.owns(key)) {
            throw new IllegalArgumentException(key + " was declared on another MiniMessageFlow");
        }
        int index = key.index();
        if (index >= this.values.length) {
            this.values = Arrays.copyOf(this.values, this.keys.size());
        }
        return index;
    }
}
