package fr.robie.minimessageflow.core;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Gives the engine of an instance as it is now, and tells the templates it compiled when it changes: a new engine
 * after its tags, resolvers or constants change. The templates are told, they don't ask: a render reads its own
 * compiled body and nothing else, and a template told of a change compiles itself again on its next use.
 * <p>
 * Internal: not part of the public API.
 */
@ApiStatus.Internal
public abstract class EngineSource {

    /**
     * The engine for the definitions as they are; null after a change, made again on its next use.
     */
    private volatile @Nullable Engine current;
    /**
     * The templates compiled through this source, held weakly: a template no one keeps is collected as before.
     */
    private final Set<Ref> templates = ConcurrentHashMap.newKeySet();
    private final ReferenceQueue<Followed> collected = new ReferenceQueue<>();

    /**
     * A template told of the changes of its source.
     */
    interface Followed {
        /**
         * Drops the compiled body: the next use compiles it again with the engine then.
         */
        void reset();
    }

    private static final class Ref extends WeakReference<Followed> {
        Ref(@NotNull Followed referent, @NotNull ReferenceQueue<Followed> queue) {
            super(referent, queue);
        }
    }

    /**
     * @return the engine now, made again after a change
     */
    public final @NotNull Engine engine() {
        Engine engine = this.current;
        return engine != null ? engine : this.rebuild();
    }

    /**
     * @return the engine now, or null when it must be made again
     */
    public final @Nullable Engine peek() {
        return this.current;
    }

    /**
     * Sets the engine, made by {@link #rebuild()} or at the start.
     *
     * @param engine the engine
     */
    public final void set(@NotNull Engine engine) {
        this.current = engine;
    }

    /**
     * After a change of definitions: the engine is made again on its next use, and each template compiled through
     * this source compiles itself again on its next use. Called holding the instance's lock.
     */
    public final void changed() {
        this.current = null;
        this.expunge();
        for (Ref ref : this.templates) {
            Followed template = ref.get();
            if (template != null) {
                template.reset();
            }
        }
    }

    /**
     * Follows a template: {@link #changed()} resets it.
     */
    final void follow(@NotNull Followed template) {
        this.expunge();
        this.templates.add(new Ref(template, this.collected));
    }

    /**
     * How many templates are followed now (for tests).
     */
    final int followed() {
        this.expunge();
        return this.templates.size();
    }

    private void expunge() {
        java.lang.ref.Reference<? extends Followed> ref;
        while ((ref = this.collected.poll()) != null) {
            this.templates.remove(ref);
        }
    }

    /**
     * Makes the engine again after a change and sets it, holding the instance's lock.
     *
     * @return the engine now
     */
    protected abstract @NotNull Engine rebuild();
}
