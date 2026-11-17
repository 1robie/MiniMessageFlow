package fr.robie.minimessageflow.paper;

import net.kyori.adventure.text.Component;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * What differs between the Paper versions supported (1.20.6 to 26), found once: the module is compiled against
 * 1.20.6, and uses a newer method through here when the server has it.
 * <p>
 * An item's name: {@code ItemStack.effectiveName()} from 1.21 (the name a player sees, with the special cases: a
 * player's head, a written book); on 1.20.6 the custom name, else the item name, else the item type's translation.
 */
abstract class PaperBridge {

    /**
     * The bridge of this server, chosen once.
     */
    static final PaperBridge CURRENT = choose();

    abstract @NotNull Component itemName(@NotNull ItemStack item);

    /**
     * Which bridge this server is on, for logs and tests.
     */
    abstract @NotNull String name();

    private static @NotNull PaperBridge choose() {
        MethodHandle effectiveName = find(ItemStack.class, "effectiveName", MethodType.methodType(Component.class));
        return effectiveName != null ? new Modern(effectiveName) : new Base();
    }

    private static @Nullable MethodHandle find(@NotNull Class<?> owner, @NotNull String name, @NotNull MethodType type) {
        try {
            return MethodHandles.publicLookup().findVirtual(owner, name, type);
        } catch (NoSuchMethodException | IllegalAccessException e) {
            return null;
        }
    }

    /**
     * Paper 1.20.6: the name from the item's meta, else its type's translation.
     */
    static final class Base extends PaperBridge {
        @Override
        @NotNull Component itemName(@NotNull ItemStack item) {
            if (item.hasItemMeta()) {
                ItemMeta meta = item.getItemMeta();
                if (meta.hasDisplayName()) {
                    Component custom = meta.displayName();
                    if (custom != null) {
                        return custom;
                    }
                }
                if (meta.hasItemName()) {
                    return meta.itemName();
                }
            }
            return Component.translatable(item.translationKey());
        }

        @Override
        @NotNull String name() {
            return "1.20.6";
        }
    }

    /**
     * Paper 1.21 and later: {@code ItemStack.effectiveName()}, called through a method handle found once.
     */
    static final class Modern extends PaperBridge {
        private final MethodHandle effectiveName;

        Modern(@NotNull MethodHandle effectiveName) {
            this.effectiveName = effectiveName;
        }

        @Override
        @NotNull Component itemName(@NotNull ItemStack item) {
            try {
                return (Component) this.effectiveName.invokeExact(item);
            } catch (RuntimeException | Error e) {
                throw e;
            } catch (Throwable e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        @NotNull String name() {
            return "1.21+";
        }
    }
}
