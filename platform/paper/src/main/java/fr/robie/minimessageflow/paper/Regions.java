package fr.robie.minimessageflow.paper;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.function.Predicate;

/**
 * Which thread may read a player's world (location, items, what it looks at). On Paper, the main thread
 * ({@code Bukkit.isOwnedByCurrentRegion(player)} is {@code isPrimaryThread()} there); on Folia, the thread of the
 * player's region, and no other.
 */
final class Regions {

    /**
     * Whether the server is Folia: regions ticked on their own threads, no main thread.
     */
    static final boolean FOLIA = exists("io.papermc.paper.threadedregions.RegionizedServer");

    /**
     * Whether this thread owns a player's region: {@code Bukkit.isOwnedByCurrentRegion} on a server, another in tests.
     */
    private static volatile Predicate<Player> owns = Bukkit::isOwnedByCurrentRegion;
    /**
     * Whether a world-reading placeholder checks the thread: on Folia; another in tests.
     */
    private static volatile boolean checked = FOLIA;

    private Regions() {
    }

    private static boolean exists(@NotNull String className) {
        try {
            Class.forName(className, false, Regions.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    /**
     * Whether this thread owns the player's region: the main thread on Paper, the player's region on Folia.
     */
    static boolean ownsPlayer(@NotNull Player player) {
        return owns.test(player);
    }

    /**
     * Whether a placeholder may read the player's world from this thread: always on Paper (as a plugin does, at its
     * own risk off the main thread), only from the player's region on Folia, where another thread must not.
     */
    static boolean mayReadWorld(@NotNull Player player) {
        return !checked || owns.test(player);
    }

    /**
     * For tests: how a region is owned, and whether world reads are checked.
     */
    static void testing(@NotNull Predicate<Player> owner, boolean check) {
        owns = owner;
        checked = check;
    }
}
