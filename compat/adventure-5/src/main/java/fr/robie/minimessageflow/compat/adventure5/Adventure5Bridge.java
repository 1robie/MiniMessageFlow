package fr.robie.minimessageflow.compat.adventure5;

import fr.robie.minimessageflow.core.AdventureBridge;
import fr.robie.minimessageflow.core.Classes;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.Style;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.VirtualComponent;
import net.kyori.adventure.text.format.ShadowColor;
import net.kyori.adventure.text.object.ObjectContents;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.nbt.api.BinaryTagHolder;
import net.kyori.adventure.text.event.ClickEvent;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Adventure 5.x. Every 5.x has shadow colours and virtual components.
 */
@ApiStatus.Internal
public final class Adventure5Bridge implements AdventureBridge {

    /**
     * Added in Adventure 5: present means 5.x (or later, until a newer bridge claims it first).
     */
    static final String ADVENTURE_5_ONLY = "net.kyori.adventure.inventory.BookLike";

    @Override
    public boolean supportsRuntime() {
        return Classes.exists(ADVENTURE_5_ONLY, Component.class);
    }

    @Override
    public int major() {
        return 5;
    }

    @Override
    public boolean hasShadowColor() {
        return true;
    }

    @Override
    public boolean hasVirtualComponents() {
        return true;
    }

    @Override
    public boolean isVirtual(@NotNull Component component) {
        return component instanceof VirtualComponent;
    }

    @Override
    public @NotNull Style shadow(@Nullable TextColor color, int alpha) {
        return Style.style().shadowColor(color == null ? ShadowColor.none() : ShadowColor.shadowColor(color, alpha)).build();
    }

    @Override
    public @Nullable Component sprite(@Nullable String atlas, @NotNull String sprite) {
        try {
            return Component.object(atlas == null
                    ? ObjectContents.sprite(Key.key(sprite))
                    : ObjectContents.sprite(Key.key(atlas), Key.key(sprite)));
        } catch (RuntimeException e) { // an invalid key: MiniMessage reports it
            return null;
        }
    }

    /**
     * As ClickTag 5.x: the page must be an integer ({@code Integer.parseInt}), else MiniMessage refuses the tag.
     */
    @Override
    public @Nullable Style clickChangePage(@NotNull String value) {
        try {
            return Style.style().clickEvent(ClickEvent.changePage(Integer.parseInt(value))).build();
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Override
    public @Nullable Style clickCustom(@NotNull String key, @Nullable String nbt) {
        try {
            return Style.style().clickEvent(ClickEvent.custom(Key.key(key), nbt == null ? null : BinaryTagHolder.binaryTagHolder(nbt))).build();
        } catch (RuntimeException e) { // an invalid key
            return null;
        }
    }

    @Override
    public @Nullable Component head(@Nullable String name) {
        return Component.object(name == null
                ? ObjectContents.playerHead().build()
                : ObjectContents.playerHead().name(name).build());
    }
}
