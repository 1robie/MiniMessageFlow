package fr.robie.minimessageflow.testkit;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.Style;
import net.kyori.adventure.text.format.TextDecoration;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * A component as the list of what a player sees: runs of text, each with the style it ends up with.
 * <p>
 * Two components that render the same give the same runs, even when their trees differ: a style flattened onto
 * the leaves and the same style on a parent, or text split differently. Compare engines with
 * {@code assertEquals(ComponentRuns.of(expected), ComponentRuns.of(actual))}, not with {@code equals} on the
 * components.
 * <ul>
 *     <li>each run carries its effective style (its own, completed by its parents');</li>
 *     <li>a decoration that is not set counts as false;</li>
 *     <li>a {@code show_text} hover is compared by its own runs;</li>
 *     <li>empty text is dropped; neighbouring text with the same style is joined;</li>
 *     <li>a component that is not text (translatable, keybind...) is one run described by {@code toString}, without
 *     its children and style, which are compared as runs.</li>
 * </ul>
 */
public final class ComponentRuns {

    /**
     * Text, or the description of a component that is not text, with its effective style.
     *
     * @param content the text, or the description
     * @param text    whether content is text (only text runs are joined)
     * @param style   the effective style, decorations all set, without a {@code show_text} hover
     * @param hover   the runs of the {@code show_text} hover, or empty
     */
    public record Run(@NotNull String content, boolean text, @NotNull Style style, @NotNull List<Run> hover) {
    }

    private ComponentRuns() {
    }

    public static @NotNull List<Run> of(@NotNull Component component) {
        List<Run> runs = new ArrayList<>();
        collect(component, Style.empty(), runs);
        return List.copyOf(runs);
    }

    private static void collect(@NotNull Component component, @NotNull Style parent, @NotNull List<Run> runs) {
        Style effective = component.style().merge(parent, Style.Merge.Strategy.IF_ABSENT_ON_TARGET);
        if (component instanceof TextComponent text) {
            add(runs, text.content(), true, effective);
        } else {
            add(runs, component.children(List.of()).style(Style.empty()).toString(), false, effective);
        }
        for (Component child : component.children()) {
            collect(child, effective, runs);
        }
    }

    private static void add(@NotNull List<Run> runs, @NotNull String content, boolean text, @NotNull Style effective) {
        if (content.isEmpty()) {
            return;
        }
        List<Run> hover = List.of();
        Style style = effective;
        HoverEvent<?> event = style.hoverEvent();
        if (event != null && event.action() == HoverEvent.Action.SHOW_TEXT) {
            hover = of((Component) event.value());
            style = style.hoverEvent(null);
        }
        for (TextDecoration decoration : TextDecoration.values()) {
            if (style.decoration(decoration) == TextDecoration.State.NOT_SET) {
                style = style.decoration(decoration, TextDecoration.State.FALSE);
            }
        }
        Run last = runs.isEmpty() ? null : runs.getLast();
        if (text && joinable(last, style, hover)) {
            runs.set(runs.size() - 1, new Run(last.content() + content, true, style, hover));
        } else {
            runs.add(new Run(content, text, style, hover));
        }
    }

    private static boolean joinable(@Nullable Run last, @NotNull Style style, @NotNull List<Run> hover) {
        return last != null && last.text() && last.style().equals(style) && last.hover().equals(hover);
    }
}
