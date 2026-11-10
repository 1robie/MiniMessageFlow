package fr.robie.minimessageflow.testkit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Ported from MessageFlow's {@code ColorConversionTest} (its Adventure part; the {@code §}-string output for
 * servers without Adventure is not in this library).
 */
class LegacyColorsTest {

    private static final String ALPHABET = "&§#<>:/xXaAfF0c9rRgk lZ";
    private static final Pattern AMPERSAND_HEX = Pattern.compile("&#[0-9a-fA-F]{6}");

    @Test
    @DisplayName("Legacy colours become MiniMessage tags like the regex version did")
    void matchesRegexVersion() {
        Random random = new Random(42);
        for (int n = 0; n < 200_000; n++) {
            String input = randomText(random);
            if (AMPERSAND_HEX.matcher(input).find()) {
                continue; // &#rrggbb is now a colour, it used to leave a stray '&'
            }
            assertEquals(OldAdventure.colorMiniMessage(input), LegacyColors.toMiniMessage(input), input);
        }
    }

    @Test
    @DisplayName("&#rrggbb is a hex colour; a hex in a tag is kept")
    void ampersandHex() {
        assertEquals("<#ff00aa>Hi", LegacyColors.toMiniMessage("&#ff00aaHi"));
        assertEquals("<red><#00FF00>x", LegacyColors.toMiniMessage("&c#00FF00x"));
        assertEquals("<color:#ff0000>a</#ff0000>", LegacyColors.toMiniMessage("<color:#ff0000>a</#ff0000>"));
    }

    private static String randomText(Random random) {
        int length = random.nextInt(24);
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        // Mix in well-formed colours so the hex branches are exercised.
        switch (random.nextInt(5)) {
            case 0 -> sb.insert(random.nextInt(sb.length() + 1), "#a1B2c3");
            case 1 -> sb.insert(random.nextInt(sb.length() + 1), "§x§a§1§b§2§c§3");
            case 2 -> sb.insert(random.nextInt(sb.length() + 1), "<#a1b2c3>");
            default -> {
            }
        }
        return sb.toString();
    }

    /**
     * MessageFlow's earlier regex implementation of {@code colorMiniMessage}.
     */
    private static final class OldAdventure {
        private static final Pattern LEGACY_HEX_PATTERN = Pattern.compile("§x(§[0-9a-fA-F]){6}");
        private static final Pattern HEX_SHORT_PATTERN = Pattern.compile("(?<!<)(?<!:)(?<!</)#([a-fA-F0-9]{6})");
        private static final Pattern BARE_HEX_PATTERN = Pattern.compile("(?<![<&])(?<!:)(?<!</)#([A-Fa-f0-9]{6})");
        private static final Map<String, String> COLORS = Map.ofEntries(
                Map.entry("0", "black"), Map.entry("1", "dark_blue"), Map.entry("2", "dark_green"),
                Map.entry("3", "dark_aqua"), Map.entry("4", "dark_red"), Map.entry("5", "dark_purple"),
                Map.entry("6", "gold"), Map.entry("7", "gray"), Map.entry("8", "dark_gray"),
                Map.entry("9", "blue"), Map.entry("a", "green"), Map.entry("b", "aqua"),
                Map.entry("c", "red"), Map.entry("d", "light_purple"), Map.entry("e", "yellow"),
                Map.entry("f", "white"), Map.entry("k", "obfuscated"), Map.entry("l", "bold"),
                Map.entry("m", "strikethrough"), Map.entry("n", "underlined"), Map.entry("o", "italic"),
                Map.entry("r", "reset"));

        static String colorMiniMessage(String message) {
            Matcher matcher = LEGACY_HEX_PATTERN.matcher(message);
            StringBuilder sb = new StringBuilder();
            while (matcher.find()) {
                matcher.appendReplacement(sb, "<#" + matcher.group().replaceAll("§x|§", "") + ">");
            }
            matcher.appendTail(sb);
            matcher = HEX_SHORT_PATTERN.matcher(sb.toString());
            StringBuilder sb2 = new StringBuilder();
            while (matcher.find()) {
                matcher.appendReplacement(sb2, "<#" + matcher.group(1) + ">");
            }
            matcher.appendTail(sb2);
            String result = BARE_HEX_PATTERN.matcher(sb2.toString()).replaceAll("<#$1>");
            for (var entry : COLORS.entrySet()) {
                String key = entry.getKey();
                String value = "<" + entry.getValue() + ">";
                result = result.replace("&" + key, value).replace("§" + key, value)
                        .replace("&" + key.toUpperCase(Locale.ROOT), value).replace("§" + key.toUpperCase(Locale.ROOT), value);
            }
            return result;
        }
    }
}
