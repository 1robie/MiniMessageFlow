package fr.robie.smoke;

import fr.robie.minimessageflow.MiniMessageFlow;
import fr.robie.minimessageflow.Placeholder;
import fr.robie.minimessageflow.Template;
import fr.robie.minimessageflow.core.AdventureBridges;
import fr.robie.minimessageflow.paper.MiniPlaceholdersBridge;
import fr.robie.minimessageflow.paper.PaperFlow;
import fr.robie.minimessageflow.paper.PaperMessages;
import fr.robie.minimessageflow.paper.PaperTranslations;
import fr.robie.minimessageflow.paper.PlaceholderApi;
import fr.robie.minimessageflow.paper.SmokeAccess;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TranslatableComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.kyori.adventure.translation.GlobalTranslator;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * Checks paper-minimessageflow on the server it runs on, without a player: each check logs PASS or FAIL, the results
 * go to {@code plugins/MiniMessageFlowSmoke/result.txt}, then the server stops.
 */
public final class SmokePlugin extends JavaPlugin {

    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();
    private final List<String> results = new ArrayList<>();
    private int failed;

    private void check(String name, Supplier<Object> actual, Object expected) {
        Object value;
        try {
            value = actual.get();
        } catch (Throwable e) {
            value = "threw " + e;
        }
        boolean pass = expected.equals(value);
        if (!pass) {
            this.failed++;
        }
        String line = (pass ? "PASS " : "FAIL ") + name + (pass ? "" : ": expected <" + expected + "> but was <" + value + ">");
        this.results.add(line);
        this.getLogger().info(line);
    }

    private static String plain(Component component) {
        return PLAIN.serialize(component);
    }

    @Override
    public void onEnable() {
        boolean folia = Boolean.getBoolean("smoke.folia");
        String minecraft = System.getProperty("smoke.minecraft", "?");
        boolean expectApi = Boolean.getBoolean("smoke.expectPlaceholderApi");
        boolean expectMini = Boolean.getBoolean("smoke.expectMiniPlaceholders");
        this.results.add("server " + Bukkit.getName() + " " + Bukkit.getVersion() + ", Minecraft " + minecraft
                + (folia ? ", Folia" : ""));

        // The library, relocated into this plugin, finds its parts.
        this.check("Folia detected", SmokeAccess::folia, folia);
        this.check("Adventure bridge", () -> AdventureBridges.current().major(), minecraft.startsWith("26.") ? 5 : 4);
        this.check("item bridge", SmokeAccess::itemBridge, minecraft.startsWith("1.20.") ? "1.20.6" : "1.21+");
        this.check("PlaceholderAPI installed", PlaceholderApi::isInstalled, expectApi);
        this.check("MiniPlaceholders installed", MiniPlaceholdersBridge::isInstalled, expectMini);

        if (expectApi) {
            this.check("PlaceholderAPI expansion registered", () -> new SmokeExpansion().register(), true);
        }
        MiniMessageFlow flow = PaperFlow.builder(this).build();

        // PlaceholderAPI, called for real.
        if (expectApi) {
            this.check("PlaceholderAPI, every form", () -> plain(flow.format(
                    "%smoke_value% <papi:smoke_value> %papi:smoke_value% <papi:smoke_value:upper> %smoke_value.upper%")),
                    "ok:value ok:value ok:value OK:VALUE OK:VALUE");
            this.check("PlaceholderAPI, unknown expansion as written", () -> plain(flow.format("%nosuchexpansion_x%")),
                    "%nosuchexpansion_x%");
            this.check("PlaceholderAPI, colour codes read", () -> flow.format("<papi:smoke_colour>").compact().color(),
                    NamedTextColor.RED);
            this.check("PlaceholderAPI, fast path", () -> flow.compile("<green><papi:smoke_value>").kind(), Template.Kind.NATIVE);
        }

        // MiniPlaceholders, its real API.
        if (expectMini) {
            this.check("MiniPlaceholders API found", SmokeAccess::miniPlaceholdersApiFound, true);
            // Not "smoke_...": PlaceholderAPI has this plugin's smoke expansion, and answers such a key first.
            this.check("MiniPlaceholders, unknown tag as written", () -> plain(flow.format("<mmfsmoke_unknown_tag>")),
                    "<mmfsmoke_unknown_tag>");
        }

        if (expectApi) {
            // Which PlaceholderAPI expansions answer server_* keys (asked before the built-in ones).
            this.results.add("info PlaceholderAPI 'server' registered: "
                    + me.clip.placeholderapi.PlaceholderAPI.isRegistered("server") + ", %server_mspt% -> '"
                    + me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(null, "%server_mspt%") + "', %server_tps% -> '"
                    + me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(null, "%server_tps%") + "'");
            this.getLogger().info(this.results.getLast());
        }

        this.results.add("info <server_mspt>: all '" + plain(flow.format("<server_mspt>")) + "', without MiniPlaceholders '"
                + plain(PaperFlow.builder(this).miniPlaceholders(false).build().format("<server_mspt>"))
                + "', built-ins only '" + plain(PaperFlow.builder(this).miniPlaceholders(false).placeholderApi(false).build()
                .format("<server_mspt>")) + "'");
        this.getLogger().info(this.results.getLast());

        // The built-in server placeholders, on a real server: alone, since an installed plugin's placeholder of the same
        // name answers first (MiniPlaceholders has its own server_mspt).
        MiniMessageFlow builtIns = PaperFlow.builder(this).miniPlaceholders(false).placeholderApi(false).build();
        this.check("server_max", () -> plain(builtIns.format("<server_max>")), String.valueOf(Bukkit.getMaxPlayers()));
        this.check("server_online", () -> plain(builtIns.format("%server_online%")), "0");
        // On Folia, outside a region (here: enabling), newer versions have no tick time: shown as written, nothing
        // thrown; Folia 1.20.6 has one.
        this.check("server_tps", () -> tickValue(plain(builtIns.format("<server_tps>")), "<server_tps>", folia), "ok");
        this.check("server_mspt", () -> tickValue(plain(builtIns.format("<server_mspt>")), "<server_mspt>", folia), "ok");
        this.check("viewer placeholder without a viewer", () -> plain(flow.format("<viewer_name>")), "<viewer_name>");

        // Real items: a custom name, an item name, neither.
        this.check("item with a custom name", () -> plain(SmokeAccess.itemName(named(Component.text("Excalibur"), null))), "Excalibur");
        this.check("item with an item name", () -> plain(SmokeAccess.itemName(named(null, Component.text("Blade")))), "Blade");
        // Paper 1.21+'s effectiveName() wraps the translated name in a text component: the key is somewhere in it.
        this.check("plain item", () -> translationKey(SmokeAccess.itemName(new ItemStack(Material.STONE))),
                "block.minecraft.stone");
        this.results.add("info plain item's name: " + SmokeAccess.itemName(new ItemStack(Material.STONE)));

        // Messages and translations: default files copied from this jar.
        this.check("messages.yml copied from the jar and loaded", () -> {
            PaperMessages messages = PaperMessages.load(this, "messages.yml", flow);
            return plain(messages.format("smoke.hello", Placeholder.of("who", "server"), null)) + " / "
                    + plain(messages.format("smoke.lines", null));
        }, "Hello server / First\nSecond");
        this.check("translations copied from the jar and rendered", () -> {
            PaperTranslations translations = PaperTranslations.builder(this, flow)
                    .defaults("en_US.yml", "fr_FR.yml").defaultLocale(Locale.US).load();
            try {
                Component bought = Component.translatable("smoke.bought", Component.text("Sword"));
                return plain(GlobalTranslator.render(bought, Locale.FRANCE)) + " / "
                        + plain(GlobalTranslator.render(bought, Locale.GERMANY));
            } finally {
                translations.close();
            }
        }, "Tu as acheté Sword / You bought Sword");

        this.results.add("RESULT " + (this.results.size() - 1 - this.failed) + " passed, " + this.failed + " failed");
        this.getLogger().info(this.results.getLast());
        try {
            Files.createDirectories(this.getDataFolder().toPath());
            Files.write(this.getDataFolder().toPath().resolve("result.txt"), this.results);
        } catch (IOException e) {
            this.getLogger().severe("can't write the result: " + e);
        }
        // Stopped once the server has started, on the global region (the main thread on Paper).
        Bukkit.getGlobalRegionScheduler().runDelayed(this, task -> Bukkit.shutdown(), 40);
    }

    private static String translationKey(Component component) {
        if (component instanceof TranslatableComponent translatable) {
            return translatable.key();
        }
        for (Component child : component.children()) {
            String key = translationKey(child);
            if (key != null) {
                return key;
            }
        }
        return null;
    }

    /**
     * "ok" for a number, or on Folia for the placeholder as written; else what was rendered.
     */
    private static String tickValue(String text, String written, boolean folia) {
        return number(text).equals("a number") || folia && text.equals(written) ? "ok" : text;
    }

    private static String number(String text) {
        return text.matches("\\d+\\.\\d\\d") ? "a number" : text;
    }

    /**
     * A server can hang once it has saved everything (seen once on Paper 1.20.6 here, at "Closing Thread Pool"): the
     * results are written by then, so the process is ended after 20 seconds.
     */
    @Override
    public void onDisable() {
        Thread watchdog = new Thread(() -> {
            try {
                Thread.sleep(20_000);
            } catch (InterruptedException e) {
                return;
            }
            Runtime.getRuntime().halt(0);
        }, "smoke-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
    }

    private static ItemStack named(Component custom, Component itemName) {
        ItemStack item = new ItemStack(Material.DIAMOND_SWORD);
        ItemMeta meta = item.getItemMeta();
        if (custom != null) {
            meta.displayName(custom);
        }
        if (itemName != null) {
            meta.itemName(itemName);
        }
        item.setItemMeta(meta);
        return item;
    }
}
