package fr.robie.minimessageflow;

import net.kyori.adventure.pointer.Pointered;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * A text with {@code %key%} placeholders compiled once, rendered as a {@code String}: a command, an amount, a
 * material name, a URL. Only placeholders are read: no tag, no colour code, and {@code <key>} is text (a command's
 * {@code <player>} stays as written). Every value is placed as written: formatted text and a resolver's text are not
 * parsed, a component gives its plain text. Modifiers apply ({@code %amount.add:1%}). A placeholder without a value
 * stays as written.
 * <p>
 * Made by {@code MiniMessageFlow.compileText}. Immutable and thread-safe.
 */
public interface TextTemplate {

    /**
     * The text as it was compiled.
     */
    @NotNull String source();

    /**
     * The keys the text asks for, in order, each once, modifiers included ({@code amount.add:1}).
     */
    @NotNull List<String> placeholders();

    /**
     * Whether every render gives the same text: no placeholder, or only constants.
     */
    boolean isStatic();

    @NotNull String render(@NotNull Placeholder placeholders, @Nullable Pointered viewer);

    @NotNull String render(@NotNull Values values, @Nullable Pointered viewer);

    default @NotNull String render(@NotNull Placeholder placeholders) {
        return this.render(placeholders, null);
    }

    default @NotNull String render() {
        return this.render(Placeholder.empty(), null);
    }
}
