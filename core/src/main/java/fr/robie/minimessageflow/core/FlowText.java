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
 * A {@link TextTemplate} that follows its instance, as {@link FlowTemplate} does: reset by its source after a
 * change, compiled again on its next use.
 * <p>
 * Internal: not part of the public API.
 */
@ApiStatus.Internal
public final class FlowText implements TextTemplate, EngineSource.Followed {

    private final EngineSource source;
    private final String text;
    private volatile @Nullable TextTemplate body;

    public FlowText(@NotNull EngineSource source, @NotNull String text) {
        this.source = source;
        this.text = text;
        this.body = source.engine().compileText(text);
        source.follow(this);
    }

    private @NotNull TextTemplate body() {
        TextTemplate body = this.body;
        return body != null ? body : this.recompile();
    }

    /**
     * As {@link FlowTemplate}: compiled again until the engine read after keeping the body is the one it used.
     */
    private @NotNull TextTemplate recompile() {
        while (true) {
            Engine engine = this.source.engine();
            TextTemplate body = engine.compileText(this.text);
            this.body = body;
            if (this.source.peek() == engine) {
                return body;
            }
        }
    }

    @Override
    public void reset() {
        this.body = null;
    }

    @Override
    public @NotNull String source() {
        return this.text;
    }

    @Override
    public @NotNull List<String> placeholders() {
        return this.body().placeholders();
    }

    @Override
    public boolean isStatic() {
        return this.body().isStatic();
    }

    @Override
    public @NotNull String render(@NotNull Placeholder placeholders, @Nullable Pointered viewer) {
        return this.body().render(placeholders, viewer);
    }

    @Override
    public @NotNull String render(@NotNull Values values, @Nullable Pointered viewer) {
        return this.body().render(values, viewer);
    }

    @Override
    public String toString() {
        return this.body().toString();
    }
}
