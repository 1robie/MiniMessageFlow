package fr.robie.minimessageflow.core;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Legacy codes to MiniMessage tags, as MessageFlow 1.1 ({@code AdventureMessageFormatter.colorMiniMessage}).
 * Stage 1 converts the text before MiniMessage reads it; stage 2 reads legacy codes in the scanner itself.
 * <p>
 * {@code §x§r§r§g§g§b§b}, {@code &#rrggbb} and a bare {@code #rrggbb} become {@code <#rrggbb>}, and {@code &c} /
 * {@code §c} become {@code <red>}. A {@code #rrggbb} right after {@code <}, {@code :} or {@code </} is already part of
 * a tag and is kept. Nothing is converted inside a {@code <click>} or {@code <insert>} tag.
 */
@ApiStatus.Internal
public final class LegacyCodes {

    private LegacyCodes() {
    }

    public static @NotNull String toMiniMessage(@NotNull String message) {
        int length = message.length();
        int i = 0;
        while (i < length) {
            char c = message.charAt(i);
            if (c == '&' || c == '§' || c == '#') {
                break;
            }
            i++;
        }
        if (i == length) {
            return message;
        }

        // From the start: a click or insert tag before the first code must be seen to be skipped.
        StringBuilder out = new StringBuilder(length + 16);
        i = 0;
        while (i < length) {
            char c = message.charAt(i);
            if (c == '<') {
                int end = eventTagEnd(message, i);
                if (end > 0) {
                    out.append(message, i, end);
                    i = end;
                    continue;
                }
            }
            if ((c == '&' || c == '§') && i + 1 < length) {
                char code = message.charAt(i + 1);
                if (c == '§' && code == 'x' && isSectionHex(message, i + 2)) {
                    out.append("<#");
                    for (int d = i + 3; d < i + 14; d += 2) {
                        out.append(message.charAt(d));
                    }
                    out.append('>');
                    i += 14;
                    continue;
                }
                if (c == '&' && code == '#' && isHex(message, i + 2)) {
                    out.append('<').append(message, i + 1, i + 8).append('>');
                    i += 8;
                    continue;
                }
                String name = colorName(code);
                if (name != null) {
                    out.append('<').append(name).append('>');
                    i += 2;
                    continue;
                }
            } else if (c == '#' && isHex(message, i + 1) && !afterTagStart(out)) {
                out.append('<').append(message, i, i + 7).append('>');
                i += 7;
                continue;
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    /**
     * The end (exclusive) of the {@code <click:...>} or {@code <insert:...>} tag that starts at {@code start}, or -1
     * when no such tag starts there. Its arguments are a command, a URL or text to copy, never formatted text: a
     * {@code &b} or {@code #rrggbb} in them is kept as written ({@code https://x.y/?a=1&b=2}).
     */
    static int eventTagEnd(@NotNull String message, int start) {
        int i;
        if (message.regionMatches(true, start + 1, "click:", 0, 6) || message.regionMatches(true, start + 1, "insert:", 0, 7)) {
            i = message.indexOf(':', start);
        } else {
            return -1;
        }
        int length = message.length();
        while (i < length) {
            char c = message.charAt(i++);
            if (c == '>') {
                return i;
            }
            if (c == '<') {
                return -1;
            }
            if (c == '\'' || c == '"') {
                while (true) {
                    if (i >= length) {
                        return -1; // quotes never closed: not a tag
                    }
                    char q = message.charAt(i++);
                    if (q == '\\') {
                        i++;
                    } else if (q == c) {
                        break;
                    }
                }
            }
        }
        return -1;
    }

    private static boolean afterTagStart(@NotNull StringBuilder out) {
        int n = out.length();
        if (n == 0) {
            return false;
        }
        char previous = out.charAt(n - 1);
        return previous == '<' || previous == ':' || (previous == '/' && n > 1 && out.charAt(n - 2) == '<');
    }

    private static boolean isHex(@NotNull String s, int from) {
        if (from + 6 > s.length()) {
            return false;
        }
        for (int i = from; i < from + 6; i++) {
            if (Character.digit(s.charAt(i), 16) < 0) {
                return false;
            }
        }
        return true;
    }

    private static boolean isSectionHex(@NotNull String s, int from) {
        if (from + 12 > s.length()) {
            return false;
        }
        for (int i = from; i < from + 12; i += 2) {
            if (s.charAt(i) != '§' || Character.digit(s.charAt(i + 1), 16) < 0) {
                return false;
            }
        }
        return true;
    }

    private static @Nullable String colorName(char code) {
        return switch (Character.toLowerCase(code)) {
            case '0' -> "black";
            case '1' -> "dark_blue";
            case '2' -> "dark_green";
            case '3' -> "dark_aqua";
            case '4' -> "dark_red";
            case '5' -> "dark_purple";
            case '6' -> "gold";
            case '7' -> "gray";
            case '8' -> "dark_gray";
            case '9' -> "blue";
            case 'a' -> "green";
            case 'b' -> "aqua";
            case 'c' -> "red";
            case 'd' -> "light_purple";
            case 'e' -> "yellow";
            case 'f' -> "white";
            case 'k' -> "obfuscated";
            case 'l' -> "bold";
            case 'm' -> "strikethrough";
            case 'n' -> "underlined";
            case 'o' -> "italic";
            case 'r' -> "reset";
            default -> null;
        };
    }
}
