package fr.robie.minimessageflow.core;

import fr.robie.minimessageflow.ModifierSet;
import fr.robie.minimessageflow.Resolver;
import fr.robie.minimessageflow.ValueTag;
import net.kyori.adventure.pointer.Pointered;
import net.kyori.adventure.text.Component;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * A {@link ValueTag} as a {@link Resolver}: {@code <name:a:b>} is the placeholder {@code name:a:b}, so the engine
 * finds it as a {@code <key>} slot, keeps the fast path, and asks it at each render.
 */
@ApiStatus.Internal
public final class ValueTagResolver implements Resolver {

    /**
     * What {@link #arguments} gives for a key this tag does not read, in {@link #parsed}.
     */
    private static final List<String> NOT_MINE = java.util.Collections.unmodifiableList(new ArrayList<>(0));
    /**
     * How many keys have their arguments kept: the keys come from messages, so the same few come again.
     */
    private static final int PARSED_KEYS = 256;

    private final String name;
    private final ValueTag tag;
    private final ModifierSet modifiers;
    /**
     * The arguments of each key asked for (or {@link #NOT_MINE}), read once: a render asks for a key twice (its
     * component, then its text), and the same keys at each render, without splitting it again.
     */
    private final BoundedCache<String, List<String>> parsed = new BoundedCache<>(PARSED_KEYS);

    public ValueTagResolver(@NotNull String name, @NotNull ValueTag tag, @NotNull ModifierSet modifiers) {
        this.name = name;
        this.tag = tag;
        this.modifiers = modifiers;
    }

    @Override
    public @Nullable String resolve(@NotNull String key, @Nullable Pointered viewer) {
        List<String> arguments = this.cachedArguments(key);
        return arguments == null ? null : this.tag.resolve(arguments, viewer);
    }

    @Override
    public @Nullable Component resolveComponent(@NotNull String key, @Nullable Pointered viewer) {
        List<String> arguments = this.cachedArguments(key);
        return arguments == null ? null : this.tag.resolveComponent(arguments, viewer);
    }

    private @Nullable List<String> cachedArguments(@NotNull String key) {
        List<String> arguments = this.parsed.get(key, k -> {
            List<String> read = this.arguments(k);
            return read == null ? NOT_MINE : read;
        });
        return arguments == NOT_MINE ? null : arguments;
    }

    /**
     * The arguments of a key this tag reads ({@code name} or {@code name:a:b}), or null for another key. A key with
     * modifiers ({@code name:a.comma}) is left to the engine, which asks again without them and applies them.
     */
    @Nullable List<String> arguments(@NotNull String key) {
        int length = this.name.length();
        if (!key.regionMatches(true, 0, this.name, 0, length)) {
            return null;
        }
        if (key.length() == length) {
            return List.of();
        }
        if (key.charAt(length) != ':' || this.hasModifiers(key, length)) {
            return null;
        }
        List<String> arguments = new ArrayList<>(2);
        int start = length + 1;
        int colon;
        while ((colon = key.indexOf(':', start)) >= 0) {
            arguments.add(key.substring(start, colon));
            start = colon + 1;
        }
        arguments.add(key.substring(start));
        return List.copyOf(arguments);
    }

    /**
     * Whether a dot after the name starts a chain of modifiers: {@code papi:x.comma}, not {@code papi:server_1.5}.
     */
    private boolean hasModifiers(@NotNull String key, int from) {
        for (int dot = key.indexOf('.', from); dot >= 0; dot = key.indexOf('.', dot + 1)) {
            if (this.modifiers.compile(key.substring(dot + 1)) != null) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String toString() {
        return "ValueTag[" + this.name + "]";
    }
}
