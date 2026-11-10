package fr.robie.minimessageflow.core;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

/**
 * Class lookups for the bridges' version and feature checks.
 */
@ApiStatus.Internal
public final class Classes {

    private Classes() {
    }

    /**
     * Whether a class exists, looked up without initialising it, from the loader of {@code near} (an Adventure
     * class, so the lookup sees the Adventure that is actually used).
     */
    public static boolean exists(@NotNull String name, @NotNull Class<?> near) {
        try {
            Class.forName(name, false, near.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }
}
