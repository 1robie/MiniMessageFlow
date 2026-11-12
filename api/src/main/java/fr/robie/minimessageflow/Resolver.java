package fr.robie.minimessageflow;

import net.kyori.adventure.pointer.Pointered;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * Gives the value of placeholders that a call doesn't (global placeholders, PlaceholderAPI...).
 * <p>
 * The text returned is formatted: MiniMessage and legacy codes in it are read. It is read on its own, so its
 * colours, decorations and unclosed tags end with it and never reach the text after the placeholder. Inside a tag
 * argument ({@code <click:run_command:'/server %server%'>}) only its plain text is used.
 * <p>
 * Called on every render that needs the key: a resolver must be thread-safe and should be fast.
 */
@FunctionalInterface
public interface Resolver {

    /**
     * @param key    the placeholder as written, without {@code %} ({@code online}, or {@code online.plural:a:b} with
     *               modifiers; when that gives null, the key before each dot is asked and the modifiers are applied
     *               to its value)
     * @param viewer who will see the message; may be null
     * @return the formatted text, or null when this resolver has no such key
     * @throws RuntimeException the key has no value from this resolver for this render, and the instance's error
     *                          handler is told
     */
    @Nullable String resolve(@NotNull String key, @Nullable Pointered viewer);

    /**
     * A resolver giving the values of a {@link Placeholder}. A text value is formatted text here (it is a
     * resolver); a component value is used as it is.
     */
    static @NotNull Resolver of(@NotNull Placeholder placeholders) {
        Objects.requireNonNull(placeholders, "placeholders cannot be null");
        return (key, viewer) -> {
            PlaceholderValue value = placeholders.get(key);
            if (value == null) {
                return null;
            }
            if (value instanceof PlaceholderValue.ComponentValue(var component)) {
                return MiniMessage.miniMessage().serialize(component);
            }
            try {
                return value.evaluate(viewer);
            } catch (RuntimeException e) {
                throw e; // the engine tells its error handler, and goes on without this value
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        };
    }
}
