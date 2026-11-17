package fr.robie.minimessageflow.paper;

import me.clip.placeholderapi.PlaceholderAPI;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The calls to PlaceholderAPI. Its own class: it is only loaded once PlaceholderAPI is known to be there, so a
 * server without it never meets {@code me.clip} classes.
 */
final class ClipHook implements PlaceholderApi.Hook {

    @Override
    public boolean isRegistered(@NotNull String identifier) {
        return PlaceholderAPI.isRegistered(identifier);
    }

    @Override
    public @NotNull String setPlaceholders(@Nullable OfflinePlayer player, @NotNull String text) {
        return PlaceholderAPI.setPlaceholders(player, text);
    }
}
