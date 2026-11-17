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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * {@link ValueTag}s as one {@link Resolver}: {@code <name:a:b>} is the placeholder {@code name:a:b}, so the engine
 * finds it as a {@code <key>} slot, keeps the fast path, and asks it at each render. The value tags added one after
 * another make one resolver, which finds the tag of a key with one lookup however many there are (a key is offered
 * to every resolver, twice per render: its component, its text). Two tags of the same name are asked in the order
 * they were added, the second when the first gives null.
 */
@ApiStatus.Internal
public final class ValueTagResolver implements Resolver {

    /**
     * How many keys have their tags and arguments kept: the keys come from messages, so the same few come again.
     */
    private static final int PARSED_KEYS = 512;

    /**
     * What a key reads: the tags of its name and its arguments; {@link #NONE} for a key no tag here reads.
     */
    private record Match(@NotNull ValueTag @NotNull [] tags, @NotNull List<String> arguments) {
    }

    private static final Match NONE = new Match(new ValueTag[0], List.of());

    private final Map<String, ValueTag[]> tags;
    private final ModifierSet modifiers;
    private final BoundedCache<String, Match> parsed = new BoundedCache<>(PARSED_KEYS);

    /**
     * @param tags      the value tags by name (lower case), each name's in the order they were added
     * @param modifiers the instance's modifiers, to leave a key with modifiers to the engine
     */
    public ValueTagResolver(@NotNull Map<String, List<ValueTag>> tags, @NotNull ModifierSet modifiers) {
        Map<String, ValueTag[]> copy = new LinkedHashMap<>();
        tags.forEach((name, list) -> copy.put(name, list.toArray(new ValueTag[0])));
        this.tags = Map.copyOf(copy);
        this.modifiers = modifiers;
    }

    @Override
    public @Nullable String resolve(@NotNull String key, @Nullable Pointered viewer) {
        Match match = this.match(key);
        for (ValueTag tag : match.tags) {
            String value = tag.resolve(match.arguments, viewer);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    @Override
    public @Nullable Component resolveComponent(@NotNull String key, @Nullable Pointered viewer) {
        Match match = this.match(key);
        for (ValueTag tag : match.tags) {
            Component value = tag.resolveComponent(match.arguments, viewer);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private @NotNull Match match(@NotNull String key) {
        return this.parsed.get(key, this::read);
    }

    /**
     * The tags and arguments of a key ({@code name} or {@code name:a:b}). A key with modifiers
     * ({@code name:a.comma}) reads none: the engine asks again without them and applies them.
     */
    private @NotNull Match read(@NotNull String key) {
        int end = 0;
        while (end < key.length() && key.charAt(end) != ':' && key.charAt(end) != '.') {
            end++;
        }
        ValueTag[] named = this.tags.get(key.substring(0, end).toLowerCase(Locale.ROOT));
        if (named == null || end < key.length() && (key.charAt(end) == '.' || this.hasModifiers(key, end))) {
            return NONE;
        }
        if (end == key.length()) {
            return new Match(named, List.of());
        }
        List<String> arguments = new ArrayList<>(2);
        int start = end + 1;
        int colon;
        while ((colon = key.indexOf(':', start)) >= 0) {
            arguments.add(key.substring(start, colon));
            start = colon + 1;
        }
        arguments.add(key.substring(start));
        return new Match(named, List.copyOf(arguments));
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
        return "ValueTags" + this.tags.keySet();
    }
}
