package fr.robie.minimessageflow.core;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.Style;
import net.kyori.adventure.text.format.TextDecoration;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Whether two components show the same thing: the same runs of text, each with the same effective style
 * (decorations not set count as false, {@code show_text} hovers compared the same way). Their trees may differ.
 * The engine uses it to check a native template against MiniMessage; the testkit's {@code ComponentRuns} is the same
 * rule for tests.
 */
@ApiStatus.Internal
final class Rendering {

    private record Run(@NotNull String content, boolean text, @NotNull Style style, @NotNull List<Run> hover) {
    }

    private Rendering() {
    }

    static boolean same(@NotNull Component a, @NotNull Component b) {
        return runs(a).equals(runs(b));
    }

    private static @NotNull List<Run> runs(@NotNull Component component) {
        List<Run> runs = new ArrayList<>();
        collect(component, Style.empty(), runs);
        return runs;
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
            hover = runs((Component) event.value());
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
