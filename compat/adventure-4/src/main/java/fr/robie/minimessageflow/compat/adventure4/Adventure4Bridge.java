package fr.robie.minimessageflow.compat.adventure4;

import fr.robie.minimessageflow.core.AdventureBridge;
import fr.robie.minimessageflow.core.Classes;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.Style;
import net.kyori.adventure.text.format.TextColor;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Adventure 4.17 - 4.x. Features added during 4.x are detected once, here.
 */
@ApiStatus.Internal
public final class Adventure4Bridge implements AdventureBridge {

    /**
     * Removed in Adventure 5: present means 4.x.
     */
    static final String ADVENTURE_4_ONLY = "net.kyori.adventure.audience.MessageType";

    private final boolean shadowColor;
    /**
     * {@code VirtualComponent}, or null before 4.18: naming the class would fail to load there.
     */
    private final @Nullable Class<?> virtualComponent;

    public Adventure4Bridge() {
        this.shadowColor = Classes.exists("net.kyori.adventure.text.format.ShadowColor", Component.class);
        this.virtualComponent = find("net.kyori.adventure.text.VirtualComponent");
    }

    private static @Nullable Class<?> find(@NotNull String name) {
        try {
            return Class.forName(name, false, Component.class.getClassLoader());
        } catch (ClassNotFoundException | LinkageError e) {
            return null;
        }
    }

    @Override
    public boolean supportsRuntime() {
        return Classes.exists(ADVENTURE_4_ONLY, Component.class);
    }

    @Override
    public int major() {
        return 4;
    }

    @Override
    public boolean hasShadowColor() {
        return this.shadowColor;
    }

    @Override
    public boolean hasVirtualComponents() {
        return this.virtualComponent != null;
    }

    @Override
    public boolean isVirtual(@NotNull Component component) {
        return this.virtualComponent != null && this.virtualComponent.isInstance(component);
    }

    /**
     * Compiled against 4.17, which has no {@code ShadowColor}: {@code <shadow>} stays with MiniMessage on 4.x.
     */
    @Override
    public @Nullable Style shadow(@Nullable TextColor color, int alpha) {
        return null;
    }

    /**
     * Object components exist from 4.25 and can't be named here: {@code <sprite>} stays with MiniMessage on 4.x.
     */
    @Override
    public @Nullable Component sprite(@Nullable String atlas, @NotNull String sprite) {
        return null;
    }

    /**
     * As {@link #sprite}: {@code <head>} stays with MiniMessage on 4.x.
     */
    @Override
    public @Nullable Component head(@Nullable String name) {
        return null;
    }

    /**
     * MiniMessage 4.x builds every click from its action and a text value: {@code change_page} keeps the page as text.
     */
    @Override
    public @Nullable Style clickChangePage(@NotNull String value) {
        try {
            return Style.style().clickEvent(net.kyori.adventure.text.event.ClickEvent.clickEvent(
                    net.kyori.adventure.text.event.ClickEvent.Action.CHANGE_PAGE, value)).build();
        } catch (RuntimeException e) {
            // Later 4.x parse the page as a number and throw; MiniMessage 4.x lets that through: leaving the message
            // to it gives the same outcome.
            return null;
        }
    }

    /**
     * No custom click before Adventure 4.22, and not nameable here: it stays with MiniMessage on 4.x.
     */
    @Override
    public @Nullable Style clickCustom(@NotNull String key, @Nullable String nbt) {
        return null;
    }
}
