package fr.robie.minimessageflow.internal;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.function.Predicate;

/**
 * Finds {@code %key%} tokens in a single pass, without regular expressions.
 * <p>
 * A {@code %} that does not open a known token is kept, and the closing {@code %} of an unknown token may open the
 * next one: in {@code 100% sure %player%}, {@code %player%} is still found.
 * <p>
 * Internal: used by {@code Placeholder} here and by the engine in {@code core}; not part of the public API.
 */
@ApiStatus.Internal
public final class PlaceholderScanner {

    /**
     * Gives the replacement of a token.
     */
    @FunctionalInterface
    public interface Resolver {
        /**
         * @param key   the token without its {@code %}
         * @param start the index of the opening {@code %} in the input
         * @return the replacement, or null to keep the token
         */
        @Nullable String resolve(@NotNull String key, int start);
    }

    private PlaceholderScanner() {
    }

    /**
     * Replaces the tokens of a text.
     *
     * @param input    the text
     * @param resolver gives each token's replacement
     * @return the replaced text, or {@code input} itself when nothing was replaced
     */
    public static @NotNull String replace(@NotNull String input, @NotNull Resolver resolver) {
        int i = input.indexOf('%');
        if (i < 0) {
            return input;
        }
        StringBuilder out = null;
        int copied = 0;
        while (true) {
            int end = input.indexOf('%', i + 1);
            if (end < 0) {
                break;
            }
            String replacement = end > i + 1 ? resolver.resolve(input.substring(i + 1, end), i) : null;
            if (replacement == null) {
                i = next(input, i, end);
                if (i < 0) {
                    break;
                }
                continue;
            }
            if (out == null) {
                out = new StringBuilder(input.length() + 16);
            }
            out.append(input, copied, i).append(replacement);
            copied = end + 1;
            i = input.indexOf('%', copied);
            if (i < 0) {
                break;
            }
        }
        if (out == null) {
            return input;
        }
        return out.append(input, copied, input.length()).toString();
    }

    /**
     * Tells whether a text has a token matching a predicate.
     *
     * @param input the text
     * @param test  tests each token, without its {@code %}
     * @return true when a token matches
     */
    public static boolean anyKey(@NotNull String input, @NotNull Predicate<String> test) {
        int i = input.indexOf('%');
        while (i >= 0) {
            int end = input.indexOf('%', i + 1);
            if (end < 0) {
                return false;
            }
            if (end > i + 1 && test.test(input.substring(i + 1, end))) {
                return true;
            }
            i = next(input, i, end);
        }
        return false;
    }

    /**
     * Where to look for the next token after an unresolved one. A token that looks like a key ({@code %a_b%}, no
     * space) keeps its closing {@code %}, so {@code %vault_eco_balance%coins%} leaves that token whole for
     * PlaceholderAPI. Otherwise ({@code 100% sure %player%}) its closing {@code %} may open the next token.
     *
     * @param input the text
     * @param start the opening {@code %} of the unresolved token
     * @param end   its closing {@code %}
     * @return the next opening {@code %}, or -1
     */
    private static int next(@NotNull String input, int start, int end) {
        if (end > start + 1 && input.lastIndexOf(' ', end) < start) {
            return input.indexOf('%', end + 1);
        }
        return end;
    }

    /**
     * Whether a token can be a placeholder key: no whitespace and none of the characters a tag, an escape or a
     * legacy code uses. Such a token is never resolved, so a scan finds the same tokens whatever the values. A
     * declared key's name ({@code builder.key(name)}) must also hold no {@code %}, which would end its token.
     *
     * @param key the token without its {@code %}
     * @return true when the token can be a placeholder
     */
    public static boolean isKey(@NotNull String key) {
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if (Character.isWhitespace(c) || c == '<' || c == '>' || c == '&' || c == '\u00a7' || c == '\\' || c == '\''
                    || c == '"' || c == '#') {
                return false;
            }
        }
        return true;
    }

    /**
     * Where the tags of a text are, as MiniMessage's tokenizer finds them ({@code TokenParser.parseString}, the same
     * from Adventure 4.17 to 5.2; {@code TagRangesTest} in core compares the two): {@code start, end} pairs (end
     * exclusive), in order. A {@code <} opens a tag only when a {@code >} closes it before another {@code <}: the
     * {@code <} of {@code I <3 you} or {@code 5 < 6} is text. Quotes inside a tag hide {@code <} and {@code >} when
     * they are closed later; a backslash escapes {@code <} (and a quote inside quotes). A {@code <} never closed is
     * text, and what follows it is read again from there.
     *
     * @param text the text
     * @return the pairs, empty when the text has no tag
     */
    public static int @NotNull [] tagRanges(@NotNull String text) {
        final int normal = 0, tag = 1, string = 2;
        int length = text.length();
        int[] ranges = new int[8];
        int count = 0;
        int state = normal;
        boolean escaped = false;
        int marker = -1; // the '<' of the tag being read
        char quote = 0;
        for (int i = 0; i < length; i++) {
            char c = text.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (c == '\\' && i + 1 < length) {
                char next = text.charAt(i + 1);
                if (state == normal) {
                    escaped = next == '<' || next == '\\';
                } else if (state == string) {
                    escaped = next == quote || next == '\\';
                } else if (next == '<') {
                    escaped = true;
                    state = normal;
                }
                if (escaped) {
                    continue;
                }
            }
            if (state == normal) {
                if (c == '<') {
                    marker = i;
                    state = tag;
                }
            } else if (state == string) {
                if (c == quote) {
                    state = tag;
                }
            } else if (c == '>') {
                if (i > marker + 1) { // "<>" is not a tag
                    if (count == ranges.length) {
                        ranges = java.util.Arrays.copyOf(ranges, count * 2);
                    }
                    ranges[count++] = marker;
                    ranges[count++] = i + 1;
                }
                state = normal;
            } else if (c == '<') {
                marker = i;
            } else if (c == '\'' || c == '"') {
                quote = c;
                if (text.indexOf(c, i + 1) >= 0) {
                    state = string;
                }
            }
            if (i == length - 1 && state == tag) {
                // A '<' never closed: text. What was in its quotes may hold tags: read again from after it.
                i = marker;
                state = normal;
            }
        }
        return count == ranges.length ? ranges : java.util.Arrays.copyOf(ranges, count);
    }

    /**
     * Whether a position is inside one of the {@link #tagRanges} of its text.
     */
    public static boolean inTag(int @NotNull [] ranges, int position) {
        for (int i = 0; i < ranges.length; i += 2) {
            if (position < ranges[i]) {
                return false;
            }
            if (position < ranges[i + 1]) {
                return true;
            }
        }
        return false;
    }

    /**
     * Puts quotes around each unquoted tag argument that holds a {@code %key%} placeholder, so that a {@code :} in its
     * value does not start another argument: {@code <click:run_command:/tp %p%>} is read as
     * {@code <click:run_command:'/tp %p%'>}. An argument with a quote or a backslash of its own is left as written,
     * as is a tag's name: the {@code %} of each placeholder left out of quotes there goes in {@code unquoted} (an index
     * in the returned text), and its value then loses its {@code :}. Quotes are read as {@link #tagRanges}
     * reads them.
     */
    public static @NotNull String quoteArguments(@NotNull String text, @NotNull Predicate<String> isKey,
                                                 @NotNull java.util.Set<Integer> unquoted) {
        if (text.indexOf('%') < 0) {
            return text;
        }
        // The placeholders, found as compile finds them: start and end (exclusive) of each.
        java.util.List<int[]> keys = new java.util.ArrayList<>();
        replace(text, (key, start) -> {
            if (!isKey.test(key)) {
                return null;
            }
            keys.add(new int[]{start, start + key.length() + 2});
            return "";
        });
        int[] tags = keys.isEmpty() ? new int[0] : tagRanges(text);
        java.util.List<int[]> wrap = new java.util.ArrayList<>(); // start, end (exclusive) of each argument to put in quotes
        java.util.List<Integer> bare = new java.util.ArrayList<>(); // the '%' of each placeholder left out of quotes, in text
        int k = 0;
        for (int t = 0; t < tags.length; t += 2) {
            int tagEnd = tags[t + 1] - 1; // its '>'
            while (k < keys.size() && keys.get(k)[0] < tags[t]) {
                k++;
            }
            if (k == keys.size() || keys.get(k)[0] >= tagEnd) {
                continue;
            }
            int partStart = tags[t] + 1;
            boolean name = true;
            boolean special = false; // a quote or a backslash in the part
            java.util.List<Integer> partKeys = new java.util.ArrayList<>(); // the part's placeholders out of quotes
            char quote = 0;
            for (int i = partStart; i <= tagEnd; i++) {
                if (i < tagEnd && k < keys.size() && keys.get(k)[0] == i) {
                    if (quote == 0) {
                        partKeys.add(i);
                    }
                    i = keys.get(k++)[1] - 1;
                    continue;
                }
                char c = i < tagEnd ? text.charAt(i) : ':';
                if (quote != 0) {
                    if (c == '\\' && i + 1 < tagEnd && (text.charAt(i + 1) == quote || text.charAt(i + 1) == '\\')) {
                        i++;
                    } else if (c == quote) {
                        quote = 0;
                    }
                    continue;
                }
                if (c == '\'' || c == '"') {
                    special = true;
                    if (text.indexOf(c, i + 1) >= 0) {
                        quote = c;
                    }
                } else if (c == '\\') {
                    special = true;
                } else if (c == ':') {
                    if (!partKeys.isEmpty()) {
                        if (name || special) {
                            bare.addAll(partKeys);
                        } else {
                            wrap.add(new int[]{partStart, i});
                        }
                    }
                    name = false;
                    special = false;
                    partKeys.clear();
                    partStart = i + 1;
                }
            }
        }
        if (wrap.isEmpty()) {
            unquoted.addAll(bare);
            return text;
        }
        StringBuilder out = new StringBuilder(text.length() + 2 * wrap.size());
        int copied = 0;
        for (int[] part : wrap) {
            out.append(text, copied, part[0]).append('\'').append(text, part[0], part[1]).append('\'');
            copied = part[1];
        }
        out.append(text, copied, text.length());
        for (int position : bare) {
            int shift = 0;
            for (int[] part : wrap) {
                if (part[1] <= position) {
                    shift += 2;
                }
            }
            unquoted.add(position + shift);
        }
        return out.toString();
    }
}
