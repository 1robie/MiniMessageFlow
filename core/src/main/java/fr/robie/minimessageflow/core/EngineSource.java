package fr.robie.minimessageflow.core;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

/**
 * Gives the engine of an instance as it is now: a new one after its tags, resolvers or constants change. A
 * template compiled by an older engine compiles itself again on its next render.
 * <p>
 * Internal: not part of the public API.
 */
@ApiStatus.Internal
@FunctionalInterface
public interface EngineSource {

    @NotNull Engine engine();
}
