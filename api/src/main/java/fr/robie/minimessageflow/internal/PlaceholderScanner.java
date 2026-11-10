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
}
