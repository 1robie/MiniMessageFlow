package fr.robie.smoke;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * {@code %smoke_<params>%}: {@code ok:<params>}. Only loaded when PlaceholderAPI is there.
 */
final class SmokeExpansion extends PlaceholderExpansion {

    @Override
    public @NotNull String getIdentifier() {
        return "smoke";
    }

    @Override
    public @NotNull String getAuthor() {
        return "MiniMessageFlow";
    }

    @Override
    public @NotNull String getVersion() {
        return "1";
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public @Nullable String onRequest(@Nullable OfflinePlayer player, @NotNull String params) {
        return params.equals("colour") ? "&cred" : "ok:" + params;
    }
}
