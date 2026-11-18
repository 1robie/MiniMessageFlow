package fr.robie.minimessageflow.internal;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.Style;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits a component at its line breaks, for {@code Template.renderLines}.
 * <p>
 * Internal: not part of the public API.
 */
@ApiStatus.Internal
public final class ComponentLines {

    private ComponentLines() {
    }

    /**
     * The lines of a component: one per {@code \n} in its text (a {@code <newline>} is one), each line keeping the
     * style open where it starts (colour, decorations, font, click, hover, insertion), so it shows as the client shows
     * the same text on several lines. A hover's text is not split, and a component other than text (a translation, a
     * key, a score) is kept whole. A component without a break is given back as it is, in a list of one, without
     * anything allocated but that list.
     */
    public static @NotNull List<Component> split(@NotNull Component component) {
        if (!hasBreak(component)) {
            return List.of(component);
        }
        Splitter splitter = new Splitter();
        splitter.walk(component, Style.empty());
        splitter.endLine();
        return List.copyOf(splitter.lines);
    }

    private static boolean hasBreak(@NotNull Component component) {
        if (component instanceof TextComponent text && text.content().indexOf('\n') >= 0) {
            return true;
        }
        for (Component child : component.children()) {
            if (hasBreak(child)) {
                return true;
            }
        }
        return false;
    }

    private static final class Splitter {
        private final List<Component> lines = new ArrayList<>(4);
        private List<Component> line = new ArrayList<>(4);

        void walk(@NotNull Component node, @NotNull Style parent) {
            Style style = parent.merge(node.style());
            if (node instanceof TextComponent text) {
                String content = text.content();
                int from = 0;
                int newline;
                while ((newline = content.indexOf('\n', from)) >= 0) {
                    this.add(content.substring(from, newline), style);
                    this.endLine();
                    from = newline + 1;
                }
                this.add(content.substring(from), style);
            } else {
                this.line.add(node.children(List.of()).style(style));
            }
            for (Component child : node.children()) {
                this.walk(child, style);
            }
        }

        private void add(@NotNull String text, @NotNull Style style) {
            if (!text.isEmpty()) {
                this.line.add(Component.text(text, style));
            }
        }

        void endLine() {
            this.lines.add(switch (this.line.size()) {
                case 0 -> Component.empty();
                case 1 -> this.line.get(0);
                default -> Component.text().append(this.line).build();
            });
            this.line = new ArrayList<>(4);
        }
    }
}
