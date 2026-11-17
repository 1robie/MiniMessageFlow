package fr.robie.minimessageflow.paper;

import net.kyori.adventure.identity.Identity;
import net.kyori.adventure.pointer.Pointered;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;
import java.util.function.Function;

/**
 * The player a render's viewer is: the viewer itself on Paper (a {@code Player} is a {@code Pointered}), else the
 * online player with its {@link Identity#UUID}.
 */
final class Viewers {

    /**
     * Finds an online player by UUID: {@code Bukkit::getPlayer} on a server, another in tests.
     */
    private static volatile Function<UUID, Player> online = Bukkit::getPlayer;

    private Viewers() {
    }

    static @Nullable Player player(@Nullable Pointered viewer) {
        if (viewer instanceof Player player) {
            return player;
        }
        if (viewer == null) {
            return null;
        }
        UUID uuid = viewer.get(Identity.UUID).orElse(null);
        return uuid == null ? null : online.apply(uuid);
    }

    /**
     * For tests: how a player is found by UUID.
     */
    static void lookup(Function<UUID, Player> lookup) {
        online = lookup;
    }
}
