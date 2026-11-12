package fr.robie.minimessageflow;

import net.kyori.adventure.pointer.Pointered;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * Gives the value of placeholders that a call doesn't (global placeholders, PlaceholderAPI...).
 * <p>
 * A value is formatted text ({@link #resolve}) or a component ({@link #resolveComponent}). For each key the
 * component is asked first; when it is null, the text is. The text returned is formatted: MiniMessage and legacy
 * codes in it are read. It is read on its own, so its colours, decorations and unclosed tags end with it and never
 * reach the text after the placeholder. Inside a tag argument ({@code <click:run_command:'/server %server%'>}) only
 * the plain text of either is used.
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
     * The value of a key as a component, inserted with its own style and never parsed: for values that are already
     * components (an item's name, a clickable player name). Asked before {@link #resolve}; return null (the default)
     * for a key whose value is text. With a modifier ({@code %key.upper%}) the component's plain text is used, as
     * plain text.
     *
     * @param key    the placeholder as written, without {@code %}
     * @param viewer who will see the message; may be null
     * @return the component, or null when this resolver has no component for the key
     * @throws RuntimeException as {@link #resolve}
     */
    default @Nullable Component resolveComponent(@NotNull String key, @Nullable Pointered viewer) {
        return null;
    }

    /**
     * A resolver giving the values of a {@link Placeholder}. A text value is formatted text here (it is a
     * resolver); a component value is used as it is.
     */
    static @NotNull Resolver of(@NotNull Placeholder placeholders) {
        Objects.requireNonNull(placeholders, "placeholders cannot be null");
        return new Resolver() {
            @Override
            public @Nullable String resolve(@NotNull String key, @Nullable Pointered viewer) {
                PlaceholderValue value = placeholders.get(key);
                if (value == null) {
                    return null;
                }
                try {
                    // A component asked as text (by a caller other than the engine): its MiniMessage text.
                    return value.isComponent() ? MiniMessage.miniMessage().serialize(value.asComponent(viewer)) : value.evaluate(viewer);
                } catch (RuntimeException e) {
                    throw e; // the engine tells its error handler, and goes on without this value
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }

            @Override
            public @Nullable Component resolveComponent(@NotNull String key, @Nullable Pointered viewer) {
                PlaceholderValue value = placeholders.get(key);
                if (value == null || !value.isComponent()) {
                    return null;
                }
                try {
                    return value.asComponent(viewer);
                } catch (RuntimeException e) {
                    throw e;
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }
        };
    }
}
