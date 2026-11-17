package fr.robie.minimessageflow;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.jetbrains.annotations.NotNull;

/**
 * A component's legacy ({@code §}) text, hex colours in Bukkit's {@code §x§r§r§g§g§b§b} form, which the server shows:
 * without them a hex colour would become the nearest of the 16 named ones. Its own class, so the serializer is made
 * when a component is first written this way (Adventure's legacy serializer is optional at runtime).
 */
final class LegacyText {

    private static final LegacyComponentSerializer SERIALIZER = LegacyComponentSerializer.builder()
            .character(LegacyComponentSerializer.SECTION_CHAR)
            .hexColors()
            .useUnusualXRepeatedCharacterHexFormat()
            .build();

    private LegacyText() {
    }

    static @NotNull String of(@NotNull Component component) {
        return SERIALIZER.serialize(component);
    }
}
