package fr.robie.minimessageflow.compat.adventure4;

import fr.robie.minimessageflow.core.AdventureBridge;
import fr.robie.minimessageflow.core.Classes;
import net.kyori.adventure.text.Component;
import org.jetbrains.annotations.ApiStatus;

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
    private final boolean virtualComponents;

    public Adventure4Bridge() {
        this.shadowColor = Classes.exists("net.kyori.adventure.text.format.ShadowColor", Component.class);
        this.virtualComponents = Classes.exists("net.kyori.adventure.text.VirtualComponent", Component.class);
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
        return this.virtualComponents;
    }
}
