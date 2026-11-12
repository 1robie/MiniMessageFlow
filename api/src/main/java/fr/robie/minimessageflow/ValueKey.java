package fr.robie.minimessageflow;

import org.jetbrains.annotations.NotNull;

/**
 * A placeholder name declared once on a {@code MiniMessageFlow} ({@code builder.key("player")}), to set its value in
 * a {@link Values} without a lookup by name. Keep it in a constant. Only works with the instance that declared it.
 */
public final class ValueKey {

    private final String name;
    private final int index;
    private final Object owner;

    ValueKey(@NotNull String name, int index, @NotNull Object owner) {
        this.name = name;
        this.index = index;
        this.owner = owner;
    }

    /**
     * The placeholder's name: {@code player} for {@code %player%} and {@code <player>}.
     */
    public @NotNull String name() {
        return this.name;
    }

    /**
     * Its place among the keys of its instance, from 0.
     */
    public int index() {
        return this.index;
    }

    @NotNull Object owner() {
        return this.owner;
    }

    @Override
    public String toString() {
        return "ValueKey[" + this.name + "]";
    }
}
