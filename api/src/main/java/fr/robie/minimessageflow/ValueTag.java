package fr.robie.minimessageflow;

import net.kyori.adventure.pointer.Pointered;
import net.kyori.adventure.text.Component;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * A tag whose value is computed at each render, for the viewer: {@code <papi:player_name>}, {@code <online>},
 * {@code <stat:jump>}. Registered with {@code MiniMessageFlow.builder().valueTag(name, tag)}.
 * <p>
 * A value tag is a placeholder with arguments: {@code <name:a:b>} and {@code %name:a:b%} are the same, both read it,
 * modifiers apply ({@code <price:comma>}, {@code %price.comma%}), and a message that uses it keeps the fast path. It
 * is asked once per render where it is written, never while a message is compiled.
 * <p>
 * Use it for any value that changes (with the viewer, with time). A {@code TagResolver} given to
 * {@code builder.tags(...)} is a tag of the message itself: it may be resolved once, when the message is compiled.
 * <p>
 * The text returned is formatted, as a {@link Resolver}'s: MiniMessage and legacy codes in it are read, on their own,
 * so its colours end with it. Inside a tag argument only its plain text is used. Called on every render that needs
 * it: it must be thread-safe and should be fast.
 */
@FunctionalInterface
public interface ValueTag {

    /**
     * @param arguments the tag's arguments, in order ({@code ["player_name"]} for {@code <papi:player_name>}), empty
     *                  for {@code <name>}. An argument named as a modifier ({@code upper}, {@code comma}) starts a
     *                  modifier and is not given here; an argument written with a {@code :} inside quotes comes as
     *                  several ({@code <papi:'time_HH:mm'>} gives {@code ["time_HH", "mm"]}: join them with
     *                  {@code ":"} when a {@code :} can be part of an argument).
     * @param viewer    who will see the message; may be null
     * @return the formatted text, or null when this tag has no value for these arguments (the tag is then shown as
     * written)
     * @throws RuntimeException no value for this render; the instance's error handler is told
     */
    @Nullable String resolve(@NotNull List<String> arguments, @Nullable Pointered viewer);

    /**
     * The value as a component, inserted with its own style and never parsed. Asked before {@link #resolve}; return
     * null (the default) when the value is text.
     *
     * @param arguments as {@link #resolve}
     * @param viewer    who will see the message; may be null
     * @return the component, or null
     * @throws RuntimeException as {@link #resolve}
     */
    default @Nullable Component resolveComponent(@NotNull List<String> arguments, @Nullable Pointered viewer) {
        return null;
    }
}
