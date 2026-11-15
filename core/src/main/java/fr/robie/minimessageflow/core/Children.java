package fr.robie.minimessageflow.core;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.Style;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.AbstractList;
import java.util.Arrays;
import java.util.List;
import java.util.RandomAccess;

/**
 * The children of a component a render builds, and the text component that holds them.
 * <p>
 * Through Adventure's API, a parent copies the list it is given ({@code children(list)}: once on 5.x, twice on 4.x,
 * whose {@code AbstractComponent} copies again). A render's list is its own and never changed after, so it is given to
 * the constructor of Adventure's text component instead, {@code TextComponentImpl(List, Style, String)}, the same on
 * every version from 4.17 to 5.2: 5.x keeps the list as it is, 4.x copies it once. The constructor is package-private:
 * it is reached when Adventure's package is open to this library (Adventure on the class path, as on Paper), and
 * checked once against the API. Otherwise, or with {@code -Dminimessageflow.directComponents=false}, the API is used.
 * <p>
 * A list is filled while the render builds it ({@link #push}), then given to one component and never changed: it is
 * immutable to everyone else ({@link AbstractList} refuses changes), and the component's final field publishes it.
 */
@ApiStatus.Internal
final class Children extends AbstractList<Component> implements RandomAccess {

    private static final Component[] NONE = new Component[0];
    /**
     * {@code (List, Style, String) -> TextComponent}, or null when the API is used.
     */
    private static final @Nullable MethodHandle CONSTRUCTOR = constructor();

    private Component[] items;
    private int size;

    Children(int capacity) {
        this.items = capacity == 0 ? NONE : new Component[capacity];
    }

    private Children(@NotNull Component @NotNull [] items, int size) {
        this.items = items;
        this.size = size;
    }

    /**
     * The components of an array that is the caller's own and is never changed after; empty components are left out,
     * as Adventure does.
     */
    static @NotNull Children of(@NotNull Component @NotNull [] items) {
        for (Component item : items) {
            if (item == Component.empty()) {
                Children children = new Children(items.length);
                for (Component kept : items) {
                    children.push(kept);
                }
                return children;
            }
        }
        return new Children(items, items.length);
    }

    /**
     * Adds a component while the list is built. An empty component is left out, as Adventure does.
     */
    void push(@NotNull Component component) {
        if (component == Component.empty()) {
            return;
        }
        if (this.size == this.items.length) {
            this.items = Arrays.copyOf(this.items, Math.max(4, this.size * 2));
        }
        this.items[this.size++] = component;
    }

    void pushAll(@NotNull Children other) {
        for (int i = 0; i < other.size; i++) {
            this.push(other.items[i]);
        }
    }

    @Override
    public Component get(int index) {
        if (index >= this.size) {
            throw new IndexOutOfBoundsException(index);
        }
        return this.items[index];
    }

    @Override
    public int size() {
        return this.size;
    }

    Component first() {
        return this.items[0];
    }

    /**
     * A text component of this content and style over the children.
     */
    static @NotNull Component text(@NotNull String content, @NotNull Style style, @NotNull Children children) {
        if (children.size == 0) {
            return Component.text(content, style);
        }
        MethodHandle constructor = CONSTRUCTOR;
        if (constructor != null) {
            try {
                return (TextComponent) constructor.invokeExact((List<?>) children, style, content);
            } catch (Throwable e) {
                throw new IllegalStateException("Adventure's text component constructor failed", e);
            }
        }
        return Component.text(content, style).children(children);
    }

    /**
     * The shell with the children: a text shell without children of its own takes them as {@link #text}; another
     * (a keybind, a translatable) through the API.
     */
    static @NotNull Component under(@NotNull Component shell, @NotNull Children children) {
        if (shell instanceof TextComponent text && shell.children().isEmpty()) {
            return text(text.content(), shell.style(), children);
        }
        return shell.children(children);
    }

    /**
     * Whether components are built with Adventure's constructor (for tests).
     */
    static boolean direct() {
        return CONSTRUCTOR != null;
    }

    private static @Nullable MethodHandle constructor() {
        if (!Boolean.parseBoolean(System.getProperty("minimessageflow.directComponents", "true"))) {
            return null;
        }
        try {
            // Found from a component: right also when Adventure is relocated.
            Class<?> impl = Component.text("x").getClass();
            MethodHandle handle = MethodHandles.privateLookupIn(impl, MethodHandles.lookup())
                    .findConstructor(impl, MethodType.methodType(void.class, List.class, Style.class, String.class))
                    .asType(MethodType.methodType(TextComponent.class, List.class, Style.class, String.class));
            // Checked once: the same component as the API gives, holding the list it was given or a copy of it.
            Children children = new Children(2);
            children.push(Component.text("b", NamedTextColor.RED));
            children.push(Component.text("c"));
            Style style = Style.style(NamedTextColor.GOLD);
            Component built = (TextComponent) handle.invokeExact((List<?>) children, style, "a");
            Component expected = Component.text("a", style).children(List.of(children.get(0), children.get(1)));
            if (!built.equals(expected) || !expected.equals(built) || built.hashCode() != expected.hashCode()
                    || built.getClass() != impl) {
                return null;
            }
            return handle;
        } catch (Throwable e) {
            // Adventure as a named module that does not open its package, or another constructor: the API.
            return null;
        }
    }
}
