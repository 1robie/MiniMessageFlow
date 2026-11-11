package fr.robie.minimessageflow.compat.adventure5;

import fr.robie.minimessageflow.core.AdventureBridge;
import fr.robie.minimessageflow.core.Classes;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.Style;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.VirtualComponent;
import net.kyori.adventure.text.format.ShadowColor;
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
}
