package fr.robie.minimessageflow.core;

import fr.robie.minimessageflow.Placeholder;
import fr.robie.minimessageflow.Template;
import fr.robie.minimessageflow.Values;
import net.kyori.adventure.pointer.Pointered;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.Style;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * A message with a placeholder inside another ({@code %changeoutput_equals_input:%page%_matcher:1_...%}), rendered in
 * the order a string placeholder parser used: its values written in first, then the placeholders they complete asked
 * of the resolvers, then the text formatted (cached by text). See {@code PlaceholderScanner.hasNested}.
 */
@ApiStatus.Internal
final class NestedTemplate implements Template {

    private final Engine engine;
    private final String source;
    private final Engine.NestedPlans plans = new Engine.NestedPlans();
    /**
     * The style set on the root of each render, or null: set before the template is given out.
     */
    private @Nullable Style style;

    NestedTemplate(@NotNull Engine engine, @NotNull String source) {
        this.engine = engine;
        this.source = source;
    }

    void applyStyle(@NotNull Style style) {
        this.style = style;
    }

    private @NotNull Component styled(@NotNull Component rendered) {
        Style style = this.style;
        return style == null ? rendered : CompiledTemplate.styled(rendered, style);
    }

    @Override
    public @NotNull String source() {
        return this.source;
    }

    @Override
    public boolean isStatic() {
        return false;
    }

    @Override
    public @NotNull Kind kind() {
        return Kind.PARSED;
    }

    @Override
    public @Nullable String fallbackReason() {
        return "a placeholder inside another one";
    }

    @Override
    public @NotNull List<String> placeholders() {
        return List.of();
    }

    @Override
    public @NotNull Component render(@NotNull Placeholder placeholders, @Nullable Pointered viewer) {
        return this.styled(this.engine.renderNested(this.engine.expandNested(this.source, this.plans, placeholders, null, viewer)));
    }

    @Override
    public @NotNull Component render(@NotNull Values values, @Nullable Pointered viewer) {
        return this.styled(this.engine.renderNested(this.engine.expandNested(this.source, this.plans, null, values, viewer)));
    }

    @Override
    public String toString() {
        return "Template[" + this.source + "]";
    }
}
