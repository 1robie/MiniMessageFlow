package fr.robie.minimessageflow;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.DoubleFunction;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Modifiers change a placeholder's value: {@code %key.modifier%}, with arguments after colons and chained with
 * dots, for example {@code %price.comma%}, {@code %name.default:Nobody.upper%} or
 * {@code %count.plural:item:items%}. They work on local and global placeholders.
 * <p>
 * A key that contains a dot is looked up as is first ({@code %server.name%} stays a placeholder of its own). An
 * unknown modifier, or one that fails (a number modifier on text, a missing argument), keeps the token as it is so
 * the mistake is visible.
 *
 * <table>
 *   <caption>Built-in modifiers</caption>
 *   <tr><td>{@code upper}, {@code lower}, {@code capitalize}, {@code trim}</td><td>change the text</td></tr>
 *   <tr><td>{@code length}, {@code empty}, {@code isnumber}</td><td>length, {@code true} if blank, {@code true} if a number</td></tr>
 *   <tr><td>{@code safe}</td><td>removes {@code ; & §} and line breaks</td></tr>
 *   <tr><td>{@code default:text}</td><td>{@code text} when the value is blank</td></tr>
 *   <tr><td>{@code max:n}, {@code pad:n}</td><td>at most {@code n} characters, zero-padded to {@code n} characters</td></tr>
 *   <tr><td>{@code int}, {@code round}, {@code floor}, {@code ceil}, {@code abs}</td><td>number rounding</td></tr>
 *   <tr><td>{@code 0f}, {@code 1f}, {@code 2f}, {@code 3f}, {@code comma}</td><td>number formatting ({@code 1,234.50})</td></tr>
 *   <tr><td>{@code add:n}, {@code sub:n}, {@code add_one}, {@code remove_one}</td><td>arithmetic</td></tr>
 *   <tr><td>{@code negative}, {@code even}</td><td>{@code true} or {@code false}</td></tr>
 *   <tr><td>{@code plural:one:other}</td><td>{@code one} when the value is 1 or -1, {@code other} otherwise</td></tr>
 *   <tr><td>{@code plural:zero:one:other}</td><td>also {@code zero} when the value is 0 (for languages such as French)</td></tr>
 * </table>
 */
public final class PlaceholderModifiers {

    /**
     * Changes a placeholder value.
     */
    @FunctionalInterface
    public interface Modifier {
        /**
         * @param value the value
         * @param args  the arguments, after the modifier name's colon
         * @return the new value, or null if the modifier does not apply (the token is kept)
         */
        @Nullable String apply(@NotNull String value, @NotNull String[] args);
    }

    private static final String[] NO_ARGS = new String[0];
    /**
     * The widest {@code pad:n}: a wider one fails (the token is kept), so a value can't build a huge string.
     */
    private static final int MAX_PAD = 256;
    private static final Map<String, Modifier> MODIFIERS = new ConcurrentHashMap<>();

    static {
        register("upper", (v, a) -> v.toUpperCase(Locale.ROOT));
        register("lower", (v, a) -> v.toLowerCase(Locale.ROOT));
        register("capitalize", (v, a) -> v.isEmpty() ? v
                : v.substring(0, 1).toUpperCase(Locale.ROOT) + v.substring(1).toLowerCase(Locale.ROOT));
        register("trim", (v, a) -> v.trim());
        register("length", (v, a) -> String.valueOf(v.length()));
        register("empty", (v, a) -> String.valueOf(v.isBlank()));
        register("isnumber", (v, a) -> String.valueOf(parseNumber(v) != null));
        register("safe", (v, a) -> {
            StringBuilder out = new StringBuilder(v.length());
            for (int i = 0; i < v.length(); i++) {
                char c = v.charAt(i);
                if (c != ';' && c != '&' && c != '§' && c != '\r' && c != '\n') {
                    out.append(c);
                }
            }
            return out.toString();
        });
        register("default", (v, a) -> v.isBlank() && a.length > 0 ? a[0] : v);
        register("max", (v, a) -> {
            int n = Integer.parseInt(a[0]);
            return v.length() <= n ? v : v.substring(0, n);
        });
        register("pad", (v, a) -> {
            int width = Integer.parseInt(a[0]);
            if (width > MAX_PAD) {
                throw new IllegalArgumentException("pad width over " + MAX_PAD + ": " + width);
            }
            return "0".repeat(Math.max(0, width - v.length())) + v;
        });

        register("int", numeric(d -> String.valueOf((long) d)));
        register("round", numeric(d -> String.valueOf(Math.round(d))));
        register("floor", numeric(d -> String.valueOf((long) Math.floor(d))));
        register("ceil", numeric(d -> String.valueOf((long) Math.ceil(d))));
        register("abs", numeric(d -> formatNumber(Math.abs(d))));
        register("negative", numeric(d -> String.valueOf(d < 0)));
        register("even", numeric(d -> String.valueOf(((long) d) % 2 == 0)));
        register("0f", numeric(d -> String.format(Locale.US, "%.0f", d)));
        register("1f", numeric(d -> String.format(Locale.US, "%.1f", d)));
        register("2f", numeric(d -> String.format(Locale.US, "%.2f", d)));
        register("3f", numeric(d -> String.format(Locale.US, "%.3f", d)));
        register("comma", numeric(d -> d == Math.rint(d)
                ? String.format(Locale.US, "%,d", (long) d)
                : String.format(Locale.US, "%,.2f", d)));
        register("add_one", numeric(d -> formatNumber(d + 1)));
        register("remove_one", numeric(d -> formatNumber(d - 1)));
        register("add", (v, a) -> {
            Double d = parseNumber(v), n = parseNumber(a[0]);
            return d == null || n == null ? null : formatNumber(d + n);
        });
        register("sub", (v, a) -> {
            Double d = parseNumber(v), n = parseNumber(a[0]);
            return d == null || n == null ? null : formatNumber(d - n);
        });
        register("plural", (v, a) -> {
            Double d = parseNumber(v);
            if (d == null || a.length < 2) {
                return null;
            }
            if (a.length >= 3) {
                return d == 0 ? a[0] : Math.abs(d) == 1 ? a[1] : a[2];
            }
            return Math.abs(d) == 1 ? a[0] : a[1];
        });
    }

    private PlaceholderModifiers() {
    }

    /**
     * Registers a modifier, replacing any with the same name.
     *
     * @param name     the name, case-insensitive
     * @param modifier the modifier
     */
    public static void register(@NotNull String name, @NotNull Modifier modifier) {
        Objects.requireNonNull(name, "name cannot be null");
        if (name.isBlank() || name.indexOf('.') >= 0 || name.indexOf(':') >= 0) {
            throw new IllegalArgumentException("name cannot be blank or contain '.' or ':'");
        }
        Objects.requireNonNull(modifier, "modifier cannot be null");
        MODIFIERS.put(name.toLowerCase(Locale.ROOT), modifier);
    }

    /**
     * Returns a registered modifier.
     *
     * @param name the name, case-insensitive
     * @return the modifier, or null
     */
    public static @Nullable Modifier get(@NotNull String name) {
        return MODIFIERS.get(name.toLowerCase(Locale.ROOT));
    }

    /**
     * Resolves a token: its own value, else the value of the key before a dot with the modifiers after it.
     *
     * @param key    the token without its {@code %}
     * @param lookup gives a key's value, or null when it has none
     * @return the value, or null to keep the token
     */
    public static @Nullable String resolve(@NotNull String key, @NotNull Function<String, String> lookup) {
        String direct = lookup.apply(key);
        if (direct != null) {
            return direct;
        }
        for (int dot = key.lastIndexOf('.'); dot > 0; dot = key.lastIndexOf('.', dot - 1)) {
            String value = lookup.apply(key.substring(0, dot));
            if (value != null) {
                return apply(value, key.substring(dot + 1));
            }
        }
        return null;
    }

    /**
     * Finds the key a token reads: the token itself, or the key before one of its dots.
     *
     * @param key    the token without its {@code %}
     * @param exists tells whether a key has a value
     * @return the key read, or null
     */
    public static @Nullable String baseKey(@NotNull String key, @NotNull Predicate<String> exists) {
        if (exists.test(key)) {
            return key;
        }
        for (int dot = key.lastIndexOf('.'); dot > 0; dot = key.lastIndexOf('.', dot - 1)) {
            String base = key.substring(0, dot);
            if (exists.test(base)) {
                return base;
            }
        }
        return null;
    }

    /**
     * Applies a chain of modifiers ({@code comma}, {@code default:0.upper}...) to a value.
     *
     * @param value the value
     * @param chain the modifiers, separated by dots
     * @return the new value, or null if a modifier is unknown or fails
     */
    public static @Nullable String apply(@NotNull String value, @NotNull String chain) {
        List<String> segments = split(chain, '.');
        String current = value;
        int i = 0;
        while (i < segments.size()) {
            String segment = segments.get(i++);
            // A dot followed by something that isn't a modifier belongs to an argument: add:1.5
            while (i < segments.size() && !isModifier(segments.get(i))) {
                segment = segment + '.' + segments.get(i++);
            }
            int colon = segment.indexOf(':');
            Modifier modifier = get(colon < 0 ? segment : segment.substring(0, colon));
            if (modifier == null) {
                return null;
            }
            String[] args = colon < 0 ? NO_ARGS : split(segment.substring(colon + 1), ':').toArray(NO_ARGS);
            try {
                current = modifier.apply(current, args);
            } catch (RuntimeException e) {
                return null;
            }
            if (current == null) {
                return null;
            }
        }
        return current;
    }

    /**
     * Whether a segment after a dot starts a new modifier, or continues the previous one's argument. Only an exact,
     * lower-case modifier name starts one, so {@code default:Mr.Length} keeps {@code Mr.Length} as its argument.
     */
    private static boolean isModifier(@NotNull String segment) {
        int colon = segment.indexOf(':');
        String name = colon < 0 ? segment : segment.substring(0, colon);
        return name.equals(name.toLowerCase(Locale.ROOT)) && get(name) != null;
    }

    private static @NotNull List<String> split(@NotNull String text, char separator) {
        List<String> parts = new ArrayList<>(4);
        int start = 0;
        int index;
        while ((index = text.indexOf(separator, start)) >= 0) {
            parts.add(text.substring(start, index));
            start = index + 1;
        }
        parts.add(text.substring(start));
        return parts;
    }

    private static @NotNull Modifier numeric(@NotNull DoubleFunction<String> function) {
        return (value, args) -> {
            Double number = parseNumber(value);
            return number == null ? null : function.apply(number);
        };
    }

    private static @Nullable Double parseNumber(@NotNull String value) {
        try {
            return Double.parseDouble(value.trim().replace(',', '.'));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static @NotNull String formatNumber(double d) {
        return d == Math.rint(d) && Math.abs(d) < 1e15 ? String.valueOf((long) d) : String.valueOf(d);
    }
}
