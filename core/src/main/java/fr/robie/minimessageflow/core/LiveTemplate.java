package fr.robie.minimessageflow.core;

import fr.robie.minimessageflow.Placeholder;
import fr.robie.minimessageflow.Template;
import fr.robie.minimessageflow.Values;
import net.kyori.adventure.pointer.Pointered;
import net.kyori.adventure.text.Component;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * A {@link Template} that follows its instance: when the instance's tags, resolvers or constants change, its next
 * use compiles the message again with them. Until then a use costs one read of the instance's engine.
 * <p>
 * Internal: not part of the public API.
 */
@ApiStatus.Internal
public final class LiveTemplate implements Template {

    private final EngineSource source;
    private final String message;
    /**
     * The message compiled by the engine it names. Two threads that see a change at once may both compile: the
     * results are the same, the last one kept.
     */
    private volatile Compiled compiled;

    private record Compiled(@NotNull Engine engine, @NotNull Template template) {
    }

    public LiveTemplate(@NotNull EngineSource source, @NotNull String message) {
        this.source = source;
        this.message = message;
        Engine engine = source.engine();
        this.compiled = new Compiled(engine, engine.compile(message));
    }

    /**
     * The message compiled by the instance's engine as it is now.
     */
    @NotNull Template current() {
        Compiled compiled = this.compiled;
        Engine engine = this.source.engine();
        if (compiled.engine != engine) {
            compiled = new Compiled(engine, engine.compile(this.message));
            this.compiled = compiled;
        }
        return compiled.template;
    }

    @Override
    public @NotNull String source() {
        return this.message;
    }

    @Override
    public boolean isStatic() {
        return this.current().isStatic();
    }

    @Override
    public @NotNull Kind kind() {
        return this.current().kind();
    }

    @Override
    public @Nullable String fallbackReason() {
        return this.current().fallbackReason();
    }

    @Override
    public @NotNull List<String> placeholders() {
        return this.current().placeholders();
    }

    @Override
    public @NotNull Component render(@NotNull Placeholder placeholders, @Nullable Pointered viewer) {
        return this.current().render(placeholders, viewer);
    }

    @Override
    public @NotNull Component render(@NotNull Values values, @Nullable Pointered viewer) {
        return this.current().render(values, viewer);
    }

    @Override
    public String toString() {
        return this.current().toString();
    }
}
