package fr.robie.minimessageflow;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.pointer.Pointered;
import net.kyori.adventure.text.Component;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Lines of a configuration compiled once (an item's lore, a help page, a scoreboard), rendered as a list: each line
 * as {@link Template#renderLines}, so a value with several lines gives several lines. Immutable and thread-safe.
 * <pre>{@code
 * TemplateList lore = text.compileList(config.getStringList("shop.lore"));
 * meta.lore(lore.render(values, player));
 * lore.send(player, values);   // one chat message per line
 * }</pre>
 */
public final class TemplateList {

    private static final TemplateList EMPTY = new TemplateList(List.of());

    private final List<Template> templates;

    private TemplateList(@NotNull List<Template> templates) {
        this.templates = templates;
    }

    /**
     * The lines, compiled: made by {@code MiniMessageFlow.compileList}.
     */
    public static @NotNull TemplateList of(@NotNull List<? extends Template> templates) {
        Objects.requireNonNull(templates, "templates cannot be null");
        return templates.isEmpty() ? EMPTY : new TemplateList(List.copyOf(templates));
    }

    /**
     * These lines, each rendered with a style on its root ({@link Template#style}): an item's lore not in italic.
     *
     * @param style the style set on the root of each line
     * @return the styled lines
     */
    public @NotNull TemplateList style(@NotNull net.kyori.adventure.text.format.Style style) {
        Objects.requireNonNull(style, "style cannot be null");
        List<Template> styled = new ArrayList<>(this.templates.size());
        for (Template template : this.templates) {
            styled.add(template.style(style));
        }
        return of(styled);
    }

    /**
     * Each line with its own style on its root, computed from the line ({@code line -> italic or not}).
     *
     * @param style gives the style of each line from its template
     * @return the styled lines
     */
    public @NotNull TemplateList style(@NotNull java.util.function.Function<Template, net.kyori.adventure.text.format.Style> style) {
        Objects.requireNonNull(style, "style cannot be null");
        List<Template> styled = new ArrayList<>(this.templates.size());
        for (Template template : this.templates) {
            styled.add(template.style(style.apply(template)));
        }
        return of(styled);
    }

    /**
     * The compiled lines, one per line given.
     */
    public @NotNull List<Template> templates() {
        return this.templates;
    }

    public int size() {
        return this.templates.size();
    }

    public boolean isEmpty() {
        return this.templates.isEmpty();
    }

    /**
     * Whether no line has a placeholder: every render gives the same lines.
     */
    public boolean isStatic() {
        for (Template template : this.templates) {
            if (!template.isStatic()) {
                return false;
            }
        }
        return true;
    }

    /**
     * The placeholders of every line, each once, in order.
     */
    public @NotNull List<String> placeholders() {
        Set<String> keys = new LinkedHashSet<>();
        for (Template template : this.templates) {
            keys.addAll(template.placeholders());
        }
        return List.copyOf(keys);
    }

    /**
     * Renders every line, each split at its line breaks.
     */
    public @NotNull List<Component> render(@NotNull Placeholder placeholders, @Nullable Pointered viewer) {
        Objects.requireNonNull(placeholders, "placeholders cannot be null");
        List<Component> lines = new ArrayList<>(this.templates.size());
        for (Template template : this.templates) {
            lines.addAll(template.renderLines(placeholders, viewer));
        }
        return lines;
    }

    /**
     * As {@link #render(Placeholder, Pointered)}, with values kept in a {@link Values}.
     */
    public @NotNull List<Component> render(@NotNull Values values, @Nullable Pointered viewer) {
        Objects.requireNonNull(values, "values cannot be null");
        List<Component> lines = new ArrayList<>(this.templates.size());
        for (Template template : this.templates) {
            lines.addAll(template.renderLines(values, viewer));
        }
        return lines;
    }

    public @NotNull List<Component> render(@NotNull Placeholder placeholders) {
        return this.render(placeholders, null);
    }

    public @NotNull List<Component> render() {
        return this.render(Placeholder.empty(), null);
    }

    /**
     * Sends every line to an audience as a chat message, rendered for it (the audience is the viewer when it is a
     * player).
     */
    public void send(@NotNull Audience audience, @NotNull Placeholder placeholders) {
        for (Component line : this.render(placeholders, audience)) {
            audience.sendMessage(line);
        }
    }

    public void send(@NotNull Audience audience, @NotNull Values values) {
        for (Component line : this.render(values, audience)) {
            audience.sendMessage(line);
        }
    }

    public void send(@NotNull Audience audience) {
        this.send(audience, Placeholder.empty());
    }

    @Override
    public String toString() {
        return "TemplateList" + this.templates;
    }
}
