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

import java.util.ArrayList;
import java.util.List;

/**
 * A {@link Template} that follows its instance: when the instance's tags, resolvers, constants or preprocessor
 * change, its source resets it and its next use compiles the message again. A render reads its compiled body and
 * renders it; it never asks whether something changed.
 * <p>
 * A style ({@link #style(Style)}) is part of the body: compiled again with it, a static message styled once.
 * <p>
 * Internal: not part of the public API.
 */
@ApiStatus.Internal
public final class FlowTemplate implements Template, EngineSource.Followed {

    private final EngineSource source;
    private final String message;
    private final @Nullable Style style;
    /**
     * The message compiled by the engine it names; null after a change, until the next use.
     */
    private volatile @Nullable Template body;
    /**
     * The body when it is a {@link CompiledTemplate} (every message but a nested one), else null: a render calls it
     * by its class, with no interface dispatch between this template and its body.
     */
    private volatile @Nullable CompiledTemplate compiledBody;

    public FlowTemplate(@NotNull EngineSource source, @NotNull String message) {
        this(source, message, null);
    }

    private FlowTemplate(@NotNull EngineSource source, @NotNull String message, @Nullable Style style) {
        this.source = source;
        this.message = message;
        this.style = style;
        this.keep(source.engine().compile(message, style));
        source.follow(this);
    }

    /**
     * The body as compiled now (for tests).
     */
    @NotNull Template compiled() {
        return this.body();
    }

    private @NotNull Template body() {
        Template body = this.body;
        return body != null ? body : this.recompile();
    }

    /**
     * Compiles the message again after a change. When another change comes while it compiles, the body may be the
     * older engine's: the source is read again after it is kept, and the message compiled again until they agree. A
     * change after that resets this body as any other.
     */
    private @NotNull Template recompile() {
        while (true) {
            Engine engine = this.source.engine();
            Template body = engine.compile(this.message, this.style);
            this.keep(body);
            if (this.source.peek() == engine) {
                return body;
            }
        }
    }

    private void keep(@NotNull Template body) {
        this.compiledBody = body instanceof CompiledTemplate compiled ? compiled : null;
        this.body = body;
    }

    @Override
    public void reset() {
        this.compiledBody = null;
        this.body = null;
    }

    @Override
    public @NotNull String source() {
        return this.message;
    }

    @Override
    public boolean isStatic() {
        return this.body().isStatic();
    }

    @Override
    public @NotNull Kind kind() {
        return this.body().kind();
    }

    @Override
    public @Nullable String fallbackReason() {
        return this.body().fallbackReason();
    }

    @Override
    public @NotNull List<String> placeholders() {
        return this.body().placeholders();
    }

    @Override
    public @NotNull Component render(@NotNull Placeholder placeholders, @Nullable Pointered viewer) {
        CompiledTemplate compiled = this.compiledBody;
        if (compiled != null) {
            return compiled.render(placeholders, viewer);
        }
        return this.body().render(placeholders, viewer);
    }

    @Override
    public @NotNull Component render(@NotNull Values values, @Nullable Pointered viewer) {
        CompiledTemplate compiled = this.compiledBody;
        if (compiled != null) {
            return compiled.render(values, viewer);
        }
        return this.body().render(values, viewer);
    }

    @Override
    public @NotNull List<Component> renderLines(@NotNull Placeholder placeholders, @Nullable Pointered viewer) {
        return this.styledLines(this.body().renderLines(placeholders, viewer));
    }

    @Override
    public @NotNull List<Component> renderLines(@NotNull Values values, @Nullable Pointered viewer) {
        return this.styledLines(this.body().renderLines(values, viewer));
    }

    /**
     * With a style, each line of several gets it on its root, as the render's root has it.
     */
    private @NotNull List<Component> styledLines(@NotNull List<Component> lines) {
        Style style = this.style;
        if (style == null || lines.size() < 2) {
            return lines;
        }
        List<Component> styled = new ArrayList<>(lines.size());
        for (Component line : lines) {
            styled.add(line.style(line.style().merge(style)));
        }
        return styled;
    }

    @Override
    public @NotNull Template style(@NotNull Style style) {
        if (style.isEmpty()) {
            return this;
        }
        return new FlowTemplate(this.source, this.message, this.style == null ? style : this.style.merge(style));
    }

    @Override
    public String toString() {
        return this.body().toString();
    }
}
