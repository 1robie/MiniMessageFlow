package fr.robie.minimessageflow.core;

import fr.robie.minimessageflow.Placeholder;
import fr.robie.minimessageflow.TextTemplate;
import fr.robie.minimessageflow.Values;
import net.kyori.adventure.pointer.Pointered;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * A {@link TextTemplate} that follows its instance, as {@link LiveTemplate} does: its resolvers and constants as
 * they are now.
 * <p>
 * Internal: not part of the public API.
 */
@ApiStatus.Internal
public final class LiveText implements TextTemplate {

    private final EngineSource source;
    private final String text;
    private volatile Compiled compiled;

    private record Compiled(@NotNull Engine engine, @NotNull TextTemplate template) {
    }

    public LiveText(@NotNull EngineSource source, @NotNull String text) {
        this.source = source;
        this.text = text;
        Engine engine = source.engine();
        this.compiled = new Compiled(engine, engine.compileText(text));
    }

    private @NotNull TextTemplate current() {
        Compiled compiled = this.compiled;
        Engine engine = this.source.engine();
        if (compiled.engine != engine) {
            compiled = new Compiled(engine, engine.compileText(this.text));
            this.compiled = compiled;
        }
        return compiled.template;
    }

    @Override
    public @NotNull String source() {
        return this.text;
    }

    @Override
    public @NotNull List<String> placeholders() {
        return this.current().placeholders();
    }

    @Override
    public boolean isStatic() {
        return this.current().isStatic();
    }

    @Override
    public @NotNull String render(@NotNull Placeholder placeholders, @Nullable Pointered viewer) {
        return this.current().render(placeholders, viewer);
    }

    @Override
    public @NotNull String render(@NotNull Values values, @Nullable Pointered viewer) {
        return this.current().render(values, viewer);
    }

    @Override
    public String toString() {
        return this.current().toString();
    }
}
