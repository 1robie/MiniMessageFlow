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
 * A text rendered as a {@code String} with a placeholder inside another, as {@link NestedTemplate}: its values written
 * in, then the placeholders they complete asked of the resolvers.
 */
@ApiStatus.Internal
final class NestedText implements TextTemplate {

    private final Engine engine;
    private final String source;
    private final Engine.NestedPlans plans = new Engine.NestedPlans();

    NestedText(@NotNull Engine engine, @NotNull String source) {
        this.engine = engine;
        this.source = source;
    }

    @Override
    public @NotNull String source() {
        return this.source;
    }

    @Override
    public @NotNull List<String> placeholders() {
        return List.of();
    }

    @Override
    public boolean isStatic() {
        return false;
    }

    @Override
    public @NotNull String render(@NotNull Placeholder placeholders, @Nullable Pointered viewer) {
        return this.engine.expandNested(this.source, this.plans, placeholders, null, viewer);
    }

    @Override
    public @NotNull String render(@NotNull Values values, @Nullable Pointered viewer) {
        return this.engine.expandNested(this.source, this.plans, null, values, viewer);
    }

    @Override
    public String toString() {
        return "TextTemplate[" + this.source + "]";
    }
}
