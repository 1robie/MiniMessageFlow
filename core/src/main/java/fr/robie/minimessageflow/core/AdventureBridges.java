package fr.robie.minimessageflow.core;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Picks the {@link AdventureBridge} of the running Adventure, once.
 * <p>
 * The bridges are found by class name, newest first, not with {@code ServiceLoader}: plugins shade and relocate
 * this library, and service files break without a shade transformer. The names are built from this class's own
 * package, so they follow a relocation.
 */
@ApiStatus.Internal
public final class AdventureBridges {

    /**
     * Simple names of the bridges, newest Adventure first. A new major adds its line at the top.
     */
    private static final List<String> BRIDGES = List.of(
            "compat.adventure5.Adventure5Bridge",
            "compat.adventure4.Adventure4Bridge"
    );

    private static final class Holder {
        private static final AdventureBridge CURRENT = select(AdventureBridges.class.getClassLoader());
    }

    private AdventureBridges() {
    }

    /**
     * The bridge of the running Adventure.
     *
     * @throws IllegalStateException (on first use, as an {@link ExceptionInInitializerError}'s cause) when no bridge
     *                               supports it
     */
    public static @NotNull AdventureBridge current() {
        return Holder.CURRENT;
    }

    /**
     * The fully qualified names of the bridges, newest first, for this class's (possibly relocated) package.
     */
    static @NotNull List<String> bridgeClassNames() {
        String core = AdventureBridges.class.getPackageName();
        String base = core.substring(0, core.lastIndexOf('.') + 1);
        List<String> names = new ArrayList<>(BRIDGES.size());
        for (String bridge : BRIDGES) {
            names.add(base + bridge);
        }
        return names;
    }

    static @NotNull AdventureBridge select(@NotNull ClassLoader loader) {
        List<String> tried = new ArrayList<>();
        for (String name : bridgeClassNames()) {
            AdventureBridge bridge = instantiate(name, loader);
            if (bridge == null) {
                tried.add(name + " (not on the class path)");
            } else if (bridge.supportsRuntime()) {
                return bridge;
            } else {
                tried.add(name + " (other Adventure version)");
            }
        }
        throw new IllegalStateException("MiniMessageFlow does not support the Adventure on the class path. Tried: "
                + String.join(", ", tried));
    }

    private static @Nullable AdventureBridge instantiate(@NotNull String name, @NotNull ClassLoader loader) {
        try {
            Class<?> type = Class.forName(name, true, loader);
            return (AdventureBridge) type.getConstructor().newInstance();
        } catch (ClassNotFoundException | LinkageError e) {
            return null;
        } catch (ReflectiveOperationException | ClassCastException e) {
            throw new IllegalStateException("Broken Adventure bridge " + name, e);
        }
    }
}
