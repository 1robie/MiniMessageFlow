package fr.robie.minimessageflow.core;

import net.kyori.adventure.text.Component;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

/**
 * Everything of Adventure that differs between the supported versions. {@code core} never calls such an API
 * itself: it asks the bridge of the running version ({@link AdventureBridges#current()}).
 * <p>
 * One implementation per Adventure major, in {@code compat/adventure-N}, compiled against the lowest N.x.
 * An implementation has a public no-argument constructor and must not touch Adventure in it beyond what
 * {@link #supportsRuntime()} needs, so a bridge for another major can be tried and refused safely.
 */
@ApiStatus.Internal
public interface AdventureBridge {

    /**
     * Whether the Adventure on the class path is the one this bridge was written for. Called once, newest bridge
     * first; must only look classes up, never throw.
     */
    boolean supportsRuntime();

    /**
     * The Adventure major this bridge is for (4, 5...).
     */
    int major();

    /**
     * Whether text can have a shadow colour ({@code <shadow>}, Adventure 4.18+).
     */
    boolean hasShadowColor();

    /**
     * Whether Adventure has virtual components ({@code <gradient>} keeps one, Adventure 4.18+).
     */
    boolean hasVirtualComponents();

    /**
     * Whether a component is a virtual component ({@code <gradient>} and {@code <rainbow>} keep one, with its own
     * copy of what they wrap). Always false where Adventure has none.
     */
    boolean isVirtual(@NotNull Component component);

    /**
     * For logs and errors: {@code "Adventure 4 (shadow colour: no, virtual components: no)"}.
     */
    default @NotNull String describe() {
        return "Adventure " + this.major()
                + " (shadow colour: " + (this.hasShadowColor() ? "yes" : "no")
                + ", virtual components: " + (this.hasVirtualComponents() ? "yes" : "no") + ")";
    }
}
