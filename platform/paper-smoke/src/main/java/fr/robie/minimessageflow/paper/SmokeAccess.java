package fr.robie.minimessageflow.paper;

/**
 * What the smoke test checks inside the module, from its package (relocated with it, so still the same package).
 */
public final class SmokeAccess {

    private SmokeAccess() {
    }

    public static String itemBridge() {
        return PaperBridge.CURRENT.name();
    }

    public static boolean folia() {
        return Regions.FOLIA;
    }

    public static boolean miniPlaceholdersApiFound() {
        return MiniPlaceholdersBridge.source("io.github.miniplaceholders.api.MiniPlaceholders") != null;
    }

    public static net.kyori.adventure.text.Component itemName(org.bukkit.inventory.ItemStack item) {
        return PaperBridge.CURRENT.itemName(item);
    }
}
