package fr.robie.minimessageflow;

import net.kyori.adventure.pointer.Pointered;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.Style;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A template whose renders get a style on their root, set over the root's own ({@link Template#style(Style)}). While
 * the template gives the same component as before (values unchanged, {@link Values#rememberRenders}), the same styled
 * component is given back, so a caller still tells nothing changed with {@code ==}. Immutable but for that memory.
 */
final class StyledTemplate implements Template {

    private final Template template;
    private final Style style;
    private final @Nullable Component fixed;
    /**
     * The last render and its styled copy, for renders without values that remember: shared by every viewer.
     */
    private volatile Styled last;

    private StyledTemplate(@NotNull Template template, @NotNull Style style) {
        this.template = template;
        this.style = style;
        this.fixed = template.isStatic() ? this.apply(template.render()) : null;
    }

    static @NotNull Template of(@NotNull Template template, @NotNull Style style) {
        Objects.requireNonNull(template, "template cannot be null");
        Objects.requireNonNull(style, "style cannot be null");
        if (style.isEmpty()) {
            return template;
        }
        if (template instanceof StyledTemplate styled) {
            return new StyledTemplate(styled.template, styled.style.merge(style));
        }
        return new StyledTemplate(template, style);
    }

    @Override
    public @NotNull String source() {
        return this.template.source();
    }

    @Override
    public boolean isStatic() {
        return this.fixed != null;
    }

    @Override
    public @NotNull Kind kind() {
        return this.template.kind();
    }

    @Override
    public @Nullable String fallbackReason() {
        return this.template.fallbackReason();
    }

    @Override
    public @NotNull List<String> placeholders() {
        return this.template.placeholders();
    }

    @Override
    public @NotNull Component render(@NotNull Placeholder placeholders, @Nullable Pointered viewer) {
        return this.fixed != null ? this.fixed : this.styled(this.template.render(placeholders, viewer), null);
    }

    @Override
    public @NotNull Component render(@NotNull Values values, @Nullable Pointered viewer) {
        return this.fixed != null ? this.fixed : this.styled(this.template.render(values, viewer), values);
    }

    @Override
    public @NotNull List<Component> renderLines(@NotNull Placeholder placeholders, @Nullable Pointered viewer) {
        return this.fixed != null ? List.of(this.fixed) : this.styledLines(this.template.renderLines(placeholders, viewer), null);
    }

    @Override
    public @NotNull List<Component> renderLines(@NotNull Values values, @Nullable Pointered viewer) {
        return this.fixed != null ? List.of(this.fixed) : this.styledLines(this.template.renderLines(values, viewer), values);
    }

    @Override
    public @NotNull Template style(@NotNull Style style) {
        return of(this, style);
    }

    private @NotNull List<Component> styledLines(@NotNull List<Component> lines, @Nullable Values values) {
        if (lines.size() == 1) {
            return List.of(this.styled(lines.getFirst(), values));
        }
        List<Component> styled = new ArrayList<>(lines.size());
        for (Component line : lines) {
            styled.add(this.apply(line));
        }
        return styled;
    }

    private @NotNull Component styled(@NotNull Component rendered, @Nullable Values values) {
        boolean own = values != null && values.remembersRenders();
        Object kept = own ? values.lastRender(this) : this.last;
        if (kept instanceof Styled styled && styled.rendered == rendered) {
            return styled.line;
        }
        Component line = this.apply(rendered);
        Styled styled = new Styled(rendered, line);
        if (own) {
            values.lastRender(this, styled);
        } else {
            this.last = styled;
        }
        return line;
    }

    private @NotNull Component apply(@NotNull Component rendered) {
        return rendered.style(rendered.style().merge(this.style));
    }

    @Override
    public String toString() {
        return "StyledTemplate[" + this.style + ", " + this.template + "]";
    }

    private record Styled(@NotNull Component rendered, @NotNull Component line) {
    }
}
