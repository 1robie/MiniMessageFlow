package fr.robie.minimessageflow;

import net.kyori.adventure.pointer.Pointered;
import net.kyori.adventure.text.Component;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

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
}
