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
import java.util.function.LongFunction;
import java.util.function.Predicate;

/**
 * Modifiers change a placeholder's value: {@code %key.modifier%}, with arguments after colons and chained with
 * dots, for example {@code %price.comma%}, {@code %name.default:Nobody.upper%} or
 * {@code %count.plural:item:items%}. They work on local and global placeholders.
 * <p>
 * A key that contains a dot is looked up as is first ({@code %server.name%} stays a placeholder of its own). An
 * unknown modifier, or one that fails (a number modifier on text, a missing argument), keeps the token as it is so
 * the mistake is visible.
 * <p>
 * The modifiers registered here are shared by everything that uses this class. Register them before compiling
 * messages: {@code <name:mine>} is read as a modifier only if {@code mine} is one when the message is compiled. For
 * modifiers of one {@code MiniMessageFlow} only, use its builder ({@code modifier(name, modifier)}, a
 * {@link ModifierSet}).
 * <p>
 * The number modifiers read and write separators in a {@link NumberStyle}: {@link NumberStyle#AUTO} here, the one set
 * on a {@link ModifierSet} or a {@code MiniMessageFlow}'s builder otherwise. A number is digits, an optional sign, the
 * style's separators and an optional exponent ({@code 1e5}): {@code NaN}, {@code Infinity} or {@code 2f} are not. A
 * modifier that needs a whole part ({@code int}, {@code round}, {@code floor}, {@code ceil}, {@code even}) fails on a
 * number past what a {@code long} holds. {@code max}, {@code length} and {@code pad} count characters as shown: an
 * emoji is one.
 *
 * <table>
 *   <caption>Built-in modifiers</caption>
 *   <tr><td>{@code upper}, {@code lower}, {@code capitalize}, {@code trim}</td><td>change the text</td></tr>
 *   <tr><td>{@code length}, {@code empty}, {@code isnumber}</td><td>length, {@code true} if blank, {@code true} if a number</td></tr>
 *   <tr><td>{@code safe}</td><td>removes {@code ; & §} and line breaks</td></tr>
 *   <tr><td>{@code default:text}</td><td>{@code text} when the value is blank</td></tr>
 *   <tr><td>{@code max:n}, {@code pad:n}</td><td>at most {@code n} characters, zero-padded to {@code n} characters</td></tr>
 *   <tr><td>{@code int}, {@code round}, {@code floor}, {@code ceil}, {@code abs}</td><td>number rounding
 *   ({@code round} rounds a half up, {@code -2.5} to {@code -2}; {@code 0f} rounds it away from zero, to
 *   {@code -3})</td></tr>
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
         * @param args  the arguments, after the modifier name's colon; read only: the same array is given again
         *              on the next call
         * @return the new value, or null if the modifier does not apply (the token is kept)
         */
        @Nullable String apply(@NotNull String value, @NotNull String[] args);
    }

    private static final String[] NO_ARGS = new String[0];
    /**
     * The widest {@code pad:n}: a wider one fails (the token is kept), so a value can't build a huge string.
     */
    private static final int MAX_PAD = 256;
    /**
     * What {@link #whole} gives for a text it does not read.
     */
    static final long NOT_WHOLE = Long.MIN_VALUE;
    /**
     * A whole number of at most this many digits is a {@code double} exactly, under the 1e15 where
     * {@link #formatNumber} stops writing whole numbers as digits.
     */
    private static final int WHOLE_DIGITS = 15;
    /**
     * The largest {@code double} that is a {@code long}: past it, a cast to {@code long} gives {@code Long.MAX_VALUE}.
     */
    private static final double LONG_RANGE = 0x1p63;
    /**
     * The modifiers registered with {@link #register}, and the built-in ones that read no number.
     */
    private static final Map<String, Modifier> MODIFIERS = new ConcurrentHashMap<>();
    /**
     * The built-in modifiers that read or write numbers, for each style. A modifier of {@link #MODIFIERS} with the
     * same name (one registered over a built-in) is used before them.
     */
    private static final Map<NumberStyle, Map<String, Modifier>> NUMBERS = new java.util.EnumMap<>(NumberStyle.class);

    static {
        register("upper", (v, a) -> v.toUpperCase(Locale.ROOT));
        register("lower", (v, a) -> v.toLowerCase(Locale.ROOT));
        register("capitalize", (v, a) -> {
            if (v.isEmpty()) {
                return v;
            }
            int first = Character.charCount(v.codePointAt(0));
            return v.substring(0, first).toUpperCase(Locale.ROOT) + v.substring(first).toLowerCase(Locale.ROOT);
        });
        register("trim", (v, a) -> v.trim());
        // Characters as shown: an emoji is one, not two UTF-16 units.
        register("length", (v, a) -> String.valueOf(v.codePointCount(0, v.length())));
        register("empty", (v, a) -> String.valueOf(v.isBlank()));
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
            if (n < 0) {
                throw new IllegalArgumentException("max under 0: " + n);
            }
            // n characters as shown: an emoji is never cut in two.
            return v.codePointCount(0, v.length()) <= n ? v : v.substring(0, v.offsetByCodePoints(0, n));
        });
        register("pad", (v, a) -> {
            int width = Integer.parseInt(a[0]);
            if (width > MAX_PAD) {
                throw new IllegalArgumentException("pad width over " + MAX_PAD + ": " + width);
            }
            return "0".repeat(Math.max(0, width - v.codePointCount(0, v.length()))) + v;
        });
        for (NumberStyle style : NumberStyle.values()) {
            NUMBERS.put(style, Map.copyOf(numberModifiers(style)));
        }
    }

    /**
     * The built-in modifiers that read or write numbers, reading and writing them in a style.
     */
    private static @NotNull Map<String, Modifier> numberModifiers(@NotNull NumberStyle style) {
        Map<String, Modifier> modifiers = new java.util.HashMap<>();
        modifiers.put("isnumber", (v, a) -> String.valueOf(whole(v) != NOT_WHOLE || parseNumber(v, style) != null));
        // A number past what a long holds has no whole part to give: the modifier fails.
        modifiers.put("int", numeric(style, d -> inLong(d) ? String.valueOf((long) d) : null, Long::toString));
        modifiers.put("round", numeric(style, d -> inLong(d) ? String.valueOf(Math.round(d)) : null, Long::toString));
        modifiers.put("floor", numeric(style, d -> inLong(d) ? String.valueOf((long) Math.floor(d)) : null, Long::toString));
        modifiers.put("ceil", numeric(style, d -> inLong(d) ? String.valueOf((long) Math.ceil(d)) : null, Long::toString));
        modifiers.put("abs", numeric(style, d -> write(formatNumber(Math.abs(d)), style), n -> Long.toString(Math.abs(n))));
        modifiers.put("negative", numeric(style, d -> String.valueOf(d < 0), n -> String.valueOf(n < 0)));
        modifiers.put("even", numeric(style, d -> inLong(d) ? String.valueOf(((long) d) % 2 == 0) : null,
                n -> String.valueOf(n % 2 == 0)));
        modifiers.put("0f", numeric(style, d -> String.format(Locale.US, "%.0f", d), Long::toString));
        modifiers.put("1f", numeric(style, d -> write(String.format(Locale.US, "%.1f", d), style),
                n -> n + (style == NumberStyle.EUROPEAN ? ",0" : ".0")));
        modifiers.put("2f", numeric(style, d -> write(String.format(Locale.US, "%.2f", d), style),
                n -> n + (style == NumberStyle.EUROPEAN ? ",00" : ".00")));
        modifiers.put("3f", numeric(style, d -> write(String.format(Locale.US, "%.3f", d), style),
                n -> n + (style == NumberStyle.EUROPEAN ? ",000" : ".000")));
        modifiers.put("comma", numeric(style, d -> write(d != Math.rint(d) ? String.format(Locale.US, "%,.2f", d)
                : inLong(d) ? grouped((long) d) : String.format(Locale.US, "%,.0f", d), style),
                n -> write(grouped(n), style)));
        modifiers.put("add_one", numeric(style, d -> write(formatNumber(d + 1), style), n -> formatWhole(n + 1, style)));
        modifiers.put("remove_one", numeric(style, d -> write(formatNumber(d - 1), style), n -> formatWhole(n - 1, style)));
        modifiers.put("add", (v, a) -> arithmetic(v, a[0], style, false));
        modifiers.put("sub", (v, a) -> arithmetic(v, a[0], style, true));
        modifiers.put("plural", (v, a) -> {
            if (a.length < 2) {
                return null;
            }
            long whole = whole(v);
            double d;
            if (whole != NOT_WHOLE) {
                d = whole;
            } else {
                Double parsed = parseNumber(v, style);
                if (parsed == null) {
                    return null;
                }
                d = parsed;
            }
            if (a.length >= 3) {
                return d == 0 ? a[0] : Math.abs(d) == 1 ? a[1] : a[2];
            }
            return Math.abs(d) == 1 ? a[0] : a[1];
        });
        return modifiers;
    }

    private static @Nullable String arithmetic(@NotNull String value, @NotNull String argument, @NotNull NumberStyle style,
                                               boolean subtract) {
        long whole = whole(value), other = whole == NOT_WHOLE ? NOT_WHOLE : whole(argument);
        if (other != NOT_WHOLE) {
            return formatWhole(subtract ? whole - other : whole + other, style);
        }
        Double d = parseNumber(value, style), n = parseNumber(argument, style);
        if (d == null || n == null) {
            return null;
        }
        double result = subtract ? d - n : d + n;
        return Double.isFinite(result) ? write(formatNumber(result), style) : null;
    }

    private static boolean inLong(double d) {
        return Math.abs(d) < LONG_RANGE;
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
        MODIFIERS.put(checkName(name), Objects.requireNonNull(modifier, "modifier cannot be null"));
    }

    /**
     * @return the name as modifiers are stored, in lower case
     */
    static @NotNull String checkName(@NotNull String name) {
        Objects.requireNonNull(name, "name cannot be null");
        if (name.isBlank() || name.indexOf('.') >= 0 || name.indexOf(':') >= 0) {
            throw new IllegalArgumentException("name cannot be blank or contain '.' or ':'");
        }
        return name.toLowerCase(Locale.ROOT);
    }

    /**
     * Returns a registered modifier.
     *
     * @param name the name, case-insensitive
     * @return the modifier, or null
     */
    public static @Nullable Modifier get(@NotNull String name) {
        return get(name, NumberStyle.AUTO);
    }

    /**
     * Returns a registered modifier, the built-in number modifiers reading and writing numbers in a style.
     *
     * @param name  the name, case-insensitive
     * @param style how the built-in number modifiers read and write numbers
     * @return the modifier, or null
     */
    public static @Nullable Modifier get(@NotNull String name, @NotNull NumberStyle style) {
        String lower = name.toLowerCase(Locale.ROOT);
        Modifier modifier = MODIFIERS.get(lower);
        return modifier != null ? modifier : NUMBERS.get(style).get(lower);
    }

    /**
     * Resolves a token: its own value, else the value of the key before a dot with the modifiers after it.
     *
     * @param key    the token without its {@code %}
     * @param lookup gives a key's value, or null when it has none
     * @return the value, or null to keep the token
     */
    public static @Nullable String resolve(@NotNull String key, @NotNull Function<String, String> lookup) {
        return resolve(key, lookup, PlaceholderModifiers::get);
    }

    static @Nullable String resolve(@NotNull String key, @NotNull Function<String, String> lookup,
                                    @NotNull Function<String, Modifier> modifiers) {
        String direct = lookup.apply(key);
        if (direct != null) {
            return direct;
        }
        for (int dot = key.lastIndexOf('.'); dot > 0; dot = key.lastIndexOf('.', dot - 1)) {
            String value = lookup.apply(key.substring(0, dot));
            if (value != null) {
                return apply(value, key.substring(dot + 1), modifiers);
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
        return apply(value, chain, PlaceholderModifiers::get);
    }

    static @Nullable String apply(@NotNull String value, @NotNull String chain, @NotNull Function<String, Modifier> modifiers) {
        Chain compiled = compile(chain, modifiers);
        return compiled == null ? null : compiled.apply(value);
    }

    /**
     * A chain of modifiers read once ({@code comma}, {@code default:0.upper}...), to apply to many values: the
     * modifiers are the ones registered when it is compiled.
     */
    public static final class Chain {
        private final Modifier[] modifiers;
        private final String[][] arguments;

        private Chain(@NotNull Modifier @NotNull [] modifiers, @NotNull String @NotNull [] @NotNull [] arguments) {
            this.modifiers = modifiers;
            this.arguments = arguments;
        }

        /**
         * @return the new value, or null if a modifier fails
         */
        public @Nullable String apply(@NotNull String value) {
            String current = value;
            for (int i = 0; i < this.modifiers.length; i++) {
                try {
                    current = this.modifiers[i].apply(current, this.arguments[i]);
                } catch (RuntimeException e) {
                    return null;
                }
                if (current == null) {
                    return null;
                }
            }
            return current;
        }
    }

    /**
     * Reads a chain of modifiers once.
     *
     * @param chain the modifiers, separated by dots
     * @return the chain, or null if a modifier is unknown
     */
    public static @Nullable Chain compile(@NotNull String chain) {
        return compile(chain, PlaceholderModifiers::get);
    }

    static @Nullable Chain compile(@NotNull String chain, @NotNull Function<String, Modifier> modifiers) {
        List<String> segments = split(chain, '.');
        List<Modifier> found = new ArrayList<>(segments.size());
        List<String[]> arguments = new ArrayList<>(segments.size());
        int i = 0;
        while (i < segments.size()) {
            String segment = segments.get(i++);
            // A dot followed by something that isn't a modifier belongs to an argument: add:1.5
            while (i < segments.size() && !isModifier(segments.get(i), modifiers)) {
                segment = segment + '.' + segments.get(i++);
            }
            int colon = segment.indexOf(':');
            Modifier modifier = modifiers.apply(colon < 0 ? segment : segment.substring(0, colon));
            if (modifier == null) {
                return null;
            }
            found.add(modifier);
            arguments.add(colon < 0 ? NO_ARGS : split(segment.substring(colon + 1), ':').toArray(NO_ARGS));
        }
        return new Chain(found.toArray(new Modifier[0]), arguments.toArray(new String[0][]));
    }

    /**
     * Whether a segment after a dot starts a new modifier, or continues the previous one's argument. Only an exact,
     * lower-case modifier name starts one, so {@code default:Mr.Length} keeps {@code Mr.Length} as its argument.
     */
    private static boolean isModifier(@NotNull String segment, @NotNull Function<String, Modifier> modifiers) {
        int colon = segment.indexOf(':');
        String name = colon < 0 ? segment : segment.substring(0, colon);
        return name.equals(name.toLowerCase(Locale.ROOT)) && modifiers.apply(name) != null;
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

    /**
     * @param function what the modifier gives for a number
     * @param whole    the same for a whole number read by {@link #whole}: it must give what {@code function} gives
     *                 for that number, without a {@code double} in between
     */
    private static @NotNull Modifier numeric(@NotNull NumberStyle style, @NotNull DoubleFunction<String> function,
                                             @NotNull LongFunction<String> whole) {
        return (value, args) -> {
            long n = whole(value);
            if (n != NOT_WHOLE) {
                return whole.apply(n);
            }
            Double number = parseNumber(value, style);
            return number == null ? null : function.apply(number);
        };
    }

    /**
     * Reads a whole number written with digits only ({@code 1250}, {@code -3}, {@code +7}, spaces around it), which
     * is what most values are: nothing is allocated, where {@link #parseNumber} makes a {@code Double}.
     *
     * @return the number, or {@link #NOT_WHOLE} for any other text, which {@link #parseNumber} reads: a decimal, an
     * exponent, more than 15 digits, and {@code -0} (a negative zero as a {@code double}, which {@code 0f} writes
     * {@code -0})
     */
    static long whole(@NotNull String value) {
        int start = 0;
        int end = value.length();
        while (start < end && value.charAt(start) <= ' ') {
            start++;
        }
        while (end > start && value.charAt(end - 1) <= ' ') {
            end--;
        }
        boolean negative = false;
        if (start < end) {
            char sign = value.charAt(start);
            if (sign == '-' || sign == '+') {
                negative = sign == '-';
                start++;
            }
        }
        int digits = end - start;
        if (digits < 1 || digits > WHOLE_DIGITS) {
            return NOT_WHOLE;
        }
        long n = 0;
        for (int i = start; i < end; i++) {
            char c = value.charAt(i);
            if (c < '0' || c > '9') {
                return NOT_WHOLE;
            }
            n = n * 10 + (c - '0');
        }
        if (negative) {
            return n == 0 ? NOT_WHOLE : -n;
        }
        return n;
    }

    /**
     * Reads a number written with digits, an optional sign, the separators of a style and an optional exponent
     * ({@code 1e5}). Anything else is not a number: {@code NaN}, {@code Infinity}, Java's {@code 2f} or {@code 10d}, hex,
     * thousands not in groups of three, a number too large for a {@code double}.
     *
     * @return the number, or null
     */
    static @Nullable Double parseNumber(@NotNull String value, @NotNull NumberStyle style) {
        String text = value.trim();
        int length = text.length();
        int start = 0;
        if (start < length && (text.charAt(start) == '-' || text.charAt(start) == '+')) {
            start++;
        }
        int end = length; // the end of the part before the exponent
        int dots = 0, commas = 0, lastDot = -1, lastComma = -1, digits = 0;
        for (int i = start; i < length; i++) {
            char c = text.charAt(i);
            if (c >= '0' && c <= '9') {
                digits++;
            } else if (c == '.') {
                dots++;
                lastDot = i;
            } else if (c == ',') {
                commas++;
                lastComma = i;
            } else if (c == 'e' || c == 'E') {
                end = i;
                break;
            } else if (!isGroupSpace(c)) {
                return null;
            }
        }
        if (digits == 0) {
            return null;
        }
        char decimal = switch (style) {
            case ENGLISH -> '.';
            case EUROPEAN -> ',';
            case AUTO -> autoDecimal(text, start, dots, commas, lastDot, lastComma);
        };
        char group = decimal == '.' ? ',' : '.';
        boolean spaces = style != NumberStyle.ENGLISH;
        StringBuilder out = new StringBuilder(length);
        if (start > 0) {
            out.append(text.charAt(0));
        }
        // The whole part: digits, in groups of three after the first when separated.
        int groupDigits = -1; // digits since the last separator, -1 before any separator
        int run = 0;
        int i = start;
        for (; i < end; i++) {
            char c = text.charAt(i);
            if (c >= '0' && c <= '9') {
                out.append(c);
                run++;
            } else if (c == group || spaces && isGroupSpace(c)) {
                if (run == 0 || groupDigits >= 0 && run != 3 || groupDigits < 0 && run > 3) {
                    return null;
                }
                groupDigits = run;
                run = 0;
            } else if (c == decimal) {
                break;
            } else {
                return null;
            }
        }
        if (groupDigits >= 0 && run != 3) {
            return null;
        }
        if (i < end) { // the decimal point, then digits only
            out.append('.');
            for (i++; i < end; i++) {
                char c = text.charAt(i);
                if (c < '0' || c > '9') {
                    return null;
                }
                out.append(c);
            }
        }
        if (end < length) { // the exponent: a sign, then digits
            out.append('e');
            i = end + 1;
            if (i < length && (text.charAt(i) == '-' || text.charAt(i) == '+')) {
                out.append(text.charAt(i++));
            }
            if (i == length) {
                return null;
            }
            for (; i < length; i++) {
                char c = text.charAt(i);
                if (c < '0' || c > '9') {
                    return null;
                }
                out.append(c);
            }
        }
        double number = Double.parseDouble(out.toString());
        return Double.isFinite(number) ? number : null;
    }

    /**
     * The decimal point of {@link NumberStyle#AUTO}, as its documentation says; {@code '.'} when there is none.
     */
    private static char autoDecimal(@NotNull String text, int start, int dots, int commas, int lastDot, int lastComma) {
        if (dots > 0 && commas > 0) {
            return lastDot > lastComma ? '.' : ',';
        }
        if (commas > 1) {
            return '.';
        }
        if (dots > 1) {
            return ',';
        }
        if (commas == 1) {
            // 1,234 is a thousand; 1,5 and 0,125 are decimals.
            int after = 0;
            for (int i = lastComma + 1; i < text.length() && text.charAt(i) >= '0' && text.charAt(i) <= '9'; i++) {
                after++;
            }
            boolean digitsOnlyAfter = lastComma + 1 + after == text.length();
            String whole = text.substring(start, lastComma).trim();
            boolean zero = whole.isEmpty() || whole.chars().allMatch(c -> c == '0');
            return after == 3 && digitsOnlyAfter && !zero ? '.' : ',';
        }
        return '.';
    }

    /**
     * A space between thousands: a space, a no-break space, a narrow no-break space (what French writes).
     */
    private static boolean isGroupSpace(char c) {
        return c == ' ' || c == ' ' || c == ' ';
    }

    /**
     * A number written the English way, in a style: {@link NumberStyle#EUROPEAN} swaps {@code .} and {@code ,}.
     */
    private static @NotNull String write(@NotNull String english, @NotNull NumberStyle style) {
        if (style != NumberStyle.EUROPEAN || english.indexOf('.') < 0 && english.indexOf(',') < 0) {
            return english;
        }
        char[] chars = english.toCharArray();
        for (int i = 0; i < chars.length; i++) {
            if (chars[i] == '.') {
                chars[i] = ',';
            } else if (chars[i] == ',') {
                chars[i] = '.';
            }
        }
        return new String(chars);
    }

    /**
     * A whole number with a comma between its thousands, as {@code String.format(Locale.US, "%,d", n)} writes it,
     * without reading a format on each call.
     */
    static @NotNull String grouped(long n) {
        if (n > -1_000_000 && n < 1_000_000) {
            // The usual sizes, as one string made at its final length.
            if (n > -1_000 && n < 1_000) {
                return Long.toString(n);
            }
            int low = (int) Math.abs(n % 1_000);
            return "" + n / 1_000 + ',' + (char) ('0' + low / 100) + (char) ('0' + low / 10 % 10) + (char) ('0' + low % 10);
        }
        String digits = Long.toString(n);
        int start = n < 0 ? 1 : 0;
        int count = digits.length() - start;
        if (count <= 3) {
            return digits;
        }
        StringBuilder out = new StringBuilder(digits.length() + (count - 1) / 3);
        int first = start + (count - 1) % 3 + 1;
        out.append(digits, 0, first);
        for (int i = first; i < digits.length(); i += 3) {
            out.append(',').append(digits, i, i + 3);
        }
        return out.toString();
    }

    /**
     * A whole number as {@link #formatNumber} writes it.
     */
    private static @NotNull String formatWhole(long n, @NotNull NumberStyle style) {
        return n > -1_000_000_000_000_000L && n < 1_000_000_000_000_000L ? Long.toString(n)
                : write(String.valueOf((double) n), style);
    }

    private static @NotNull String formatNumber(double d) {
        return d == Math.rint(d) && Math.abs(d) < 1e15 ? String.valueOf((long) d) : String.valueOf(d);
    }
}
