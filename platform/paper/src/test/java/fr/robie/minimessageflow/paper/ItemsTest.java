package fr.robie.minimessageflow.paper;

import fr.robie.minimessageflow.MiniMessageFlow;
import fr.robie.minimessageflow.Placeholder;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TranslatableComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ItemsTest {

    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    /**
     * An item without a server: its meta, its translation key and how chat shows it, given.
     */
    private static final class TestItem extends ItemStack {
        private final String key;
        private final ItemMeta meta;

        TestItem(String key, Component custom, Component itemName) {
            this.key = key;
            Map<String, Fakes.Answer> answers = new HashMap<>();
            answers.put("hasDisplayName", args -> custom != null);
            answers.put("displayName", args -> custom);
            answers.put("hasItemName", args -> itemName != null);
            answers.put("itemName", args -> itemName);
            this.meta = custom == null && itemName == null ? null : Fakes.proxy(ItemMeta.class, answers);
        }

        @Override
        public boolean hasItemMeta() {
            return this.meta != null;
        }

        @Override
        public ItemMeta getItemMeta() {
            return this.meta;
        }

        @Override
        public @NotNull String translationKey() {
            return this.key;
        }

        @Override
        public @NotNull Component displayName() {
            return Component.text("[" + this.key + "]", NamedTextColor.AQUA);
        }
    }

    @Test
    @DisplayName("The bridge of the Paper the tests run on: effectiveName() from 1.21, the meta on 1.20.6")
    void bridgeChosen() {
        String paper = System.getProperty("minimessageflow.test.paperVersion", "1.20.6");
        assertEquals(paper.startsWith("1.20.") ? "1.20.6" : "1.21+", PaperBridge.CURRENT.name(), "Paper " + paper);
    }

    @Test
    @DisplayName("On 1.20.6: the custom name, else the item name, else the type's translation")
    void baseNames() {
        PaperBridge base = new PaperBridge.Base();
        Component custom = Component.text("Excalibur", NamedTextColor.GOLD);
        assertEquals(custom, base.itemName(new TestItem("item.minecraft.diamond_sword", custom, Component.text("Sword"))));
        assertEquals(Component.text("Sword"), base.itemName(new TestItem("item.minecraft.diamond_sword", null, Component.text("Sword"))));
        Component plain = base.itemName(new TestItem("item.minecraft.stone", null, null));
        assertEquals("item.minecraft.stone", ((TranslatableComponent) plain).key());
    }

    private static Player holding(Map<EquipmentSlot, ItemStack> items, Entity target, Entity vehicle) {
        PlayerInventory inventory = Fakes.proxy(PlayerInventory.class, Map.of(
                "getItem", args -> items.get((EquipmentSlot) args[0])));
        Player base = Fakes.player("Steve", UUID.randomUUID());
        Map<String, Fakes.Answer> answers = new HashMap<>();
        answers.put("getInventory", args -> inventory);
        answers.put("getTargetEntity", args -> args[0].equals(10) || args[0].equals(30) ? target : null);
        answers.put("getVehicle", args -> vehicle);
        answers.put("getName", args -> base.getName());
        answers.put("getUniqueId", args -> base.getUniqueId());
        return Fakes.proxy(Player.class, answers);
    }

    @Test
    @DisplayName("viewer_item and its slots, viewer_item_display, viewer_target and its distance, viewer_vehicle")
    void placeholders() {
        Entity zombie = Fakes.proxy(Entity.class, Map.of("name", args -> Component.text("Zombie")));
        Entity horse = Fakes.proxy(Entity.class, Map.of("name", args -> Component.text("Horse")));
        Map<EquipmentSlot, ItemStack> items = Map.of(
                EquipmentSlot.HAND, new TestItem("sword", null, Component.text("Sword")),
                EquipmentSlot.OFF_HAND, new TestItem("shield", null, Component.text("Shield")));
        Player player = holding(items, zombie, horse);
        MiniMessageFlow.Builder builder = MiniMessageFlow.builder();
        PaperPlaceholders.builder().applyTo(builder, () -> Fakes.SERVER);
        MiniMessageFlow flow = builder.build();
        // The bridge of these tests' Paper is the base one only on 1.20.6: check the names it gives on its own.
        String names = PaperBridge.CURRENT instanceof PaperBridge.Base ? "Sword Shield " : "";
        String message = (names.isEmpty() ? "" : "<viewer_item> <viewer_item:offhand> ")
                + "<viewer_item_display> <viewer_target> <viewer_target:30> <viewer_target:5> <viewer_vehicle>";
        assertEquals(names + "[sword] Zombie Zombie <viewer_target:5> Horse",
                PLAIN.serialize(flow.format(message, Placeholder.empty(), player)));
    }

    @Test
    @DisplayName("A slot that does not exist: the placeholder fails and is shown as written")
    void badSlot() {
        Player player = holding(Map.of(), null, null);
        MiniMessageFlow.Builder builder = MiniMessageFlow.builder().errorHandler((key, error) -> { });
        PaperPlaceholders.builder().applyTo(builder, () -> Fakes.SERVER);
        assertEquals("<viewer_item:pocket>", PLAIN.serialize(builder.build().format("<viewer_item:pocket>", Placeholder.empty(), player)));
    }
}
