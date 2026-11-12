package fr.robie.minimessageflow.core;

import fr.robie.minimessageflow.ModifierSet;
import net.kyori.adventure.text.minimessage.tag.resolver.ArgumentQueue;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;


/**
 * The placeholder key a {@code <name:args>} tag reads, written the {@code %name.modifier:arg%} way.
 * <p>
 * An argument that is a modifier's name starts a modifier; any other argument belongs to the modifier before it:
 * {@code <n:plural:item:items>} is {@code n.plural:item:items}, {@code <name:upper:max:16>} is
 * {@code name.upper.max:16}.
 */
@ApiStatus.Internal
public final class TagKeys {

    private TagKeys() {
    }

    public static @NotNull String key(@NotNull String name, @NotNull ArgumentQueue arguments, @NotNull ModifierSet modifiers) {
        if (!arguments.hasNext()) {
            return name;
        }
        StringBuilder key = new StringBuilder(name);
        while (arguments.hasNext()) {
            String argument = arguments.pop().value();
            key.append(modifiers.isModifier(argument) ? '.' : ':').append(argument);
        }
        return key.toString();
    }

    public static @NotNull String key(@NotNull String name, @NotNull java.util.List<String> arguments, @NotNull ModifierSet modifiers) {
        if (arguments.isEmpty()) {
            return name;
        }
        StringBuilder key = new StringBuilder(name);
        for (String argument : arguments) {
            key.append(modifiers.isModifier(argument) ? '.' : ':').append(argument);
        }
        return key.toString();
    }
}
