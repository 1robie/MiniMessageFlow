package fr.robie.minimessageflow;

import net.kyori.adventure.pointer.Pointered;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * A message compiled once, rendered many times. Immutable and thread-safe.
 * <p>
 * Placeholders are written {@code %name%} or {@code <name>}, with modifiers ({@code %price.comma%},
 * {@code <price:comma>}). At render, a placeholder takes, in order: the value given to the call, the value of the
 * instance's resolvers, or else stays as written.
 */
public interface Template {

    /**
     * The message as written.
     */
    @NotNull String source();

    /**
     * Whether the message has no placeholder at all: every render returns the same component.
     */
    boolean isStatic();

    /**
     * How a message renders, from fastest to slowest.
     */
    enum Kind {
        /**
         * No placeholder: every render returns the same component.
         */
        STATIC,
        /**
         * Compiled without MiniMessage: a render only places the values. A render can still go through MiniMessage
         * for a value it can't place itself (a styled value under a gradient, a {@code :} in an argument without
         * quotes, a {@code #} in a hover's text or a translation argument).
         */
        NATIVE,
        /**
         * Parsed once by MiniMessage with a marker where each value goes: a render swaps the markers.
         */
        MARKERS,
        /**
         * Parsed by MiniMessage with its values, once per set of values (cached): the slowest, for messages whose
         * values MiniMessage must see (a placeholder under a gradient in a hover's text, in a tag's name...).
         */
        PARSED
    }

    /**
     * How this message renders when every placeholder has a value: to find the messages worth rewriting. A
     * {@code <name>} without a value is read as MiniMessage reads an unknown tag, which can take another path.
     */
    @NotNull Kind kind();

    /**
     * Why this message is not compiled natively, for a {@link Kind#MARKERS} or {@link Kind#PARSED} message: the tag
     * that is left to MiniMessage as it is written, or what else stood in the way. Null for a {@link Kind#STATIC}
     * or {@link Kind#NATIVE} message. A hint to find the messages worth rewriting; the wording may change.
     */
    default @Nullable String fallbackReason() {
        return null;
    }

    /**
     * The placeholders of the message, each once, as a render asks for them: {@code item} for {@code %item%} or
     * {@code <item>}, {@code price.comma} for {@code %price.comma%} or {@code <price:comma>}. Every {@code <name>}
     * that is not a tag is one, so a misspelt tag shows here too: compare with the keys you give to find typos.
     */
    @NotNull List<String> placeholders();

    /**
     * Renders the message.
     *
     * @param placeholders the values of this call; a text value is never read as MiniMessage or legacy codes
     * @param viewer       who will see it, for viewer-specific values; may be null
     * @return the component
     */
    @NotNull Component render(@NotNull Placeholder placeholders, @Nullable Pointered viewer);

    default @NotNull Component render(@NotNull Placeholder placeholders) {
        return this.render(placeholders, null);
    }

    default @NotNull Component render() {
        return this.render(Placeholder.empty(), null);
    }

    /**
     * Renders the message with values kept in a {@link Values}, made once and set again for each send
     * ({@code MiniMessageFlow.newValues()}): nothing is built per send but the component.
     *
     * @param values the values of this call, from the instance that compiled this message
     * @param viewer who will see it, for the resolvers; may be null
     * @return the component
     * @throws IllegalArgumentException the values are another instance's
     */
    default @NotNull Component render(@NotNull Values values, @Nullable Pointered viewer) {
        return this.render(values.toPlaceholder(), viewer);
    }

    default @NotNull Component render(@NotNull Values values) {
        return this.render(values, null);
    }

    /**
     * Renders the message as plain text, without colours or events: for a console, a log, a title compared as text.
     */
    default @NotNull String renderPlain(@NotNull Placeholder placeholders, @Nullable Pointered viewer) {
        return PlainTextComponentSerializer.plainText().serialize(this.render(placeholders, viewer));
    }

    default @NotNull String renderPlain(@NotNull Placeholder placeholders) {
        return this.renderPlain(placeholders, null);
    }
}
