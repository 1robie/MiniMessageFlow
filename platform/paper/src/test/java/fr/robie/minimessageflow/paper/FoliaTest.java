package fr.robie.minimessageflow.paper;

import fr.robie.minimessageflow.MiniMessageFlow;
import fr.robie.minimessageflow.Placeholder;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Folia's rule, with the thread that owns a player given: a placeholder reading the player's world is only read on
 * that thread.
 */
class FoliaTest {

    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();
    private final AtomicBoolean owned = new AtomicBoolean();
    private final Player steve = Fakes.player("Steve", UUID.randomUUID());

    @AfterEach
    void paper() {
        Regions.testing(player -> true, false);
    }

    @Test
    @DisplayName("Folia: off the player's region, a world placeholder is shown as written and reported; others are read")
    void worldReads() {
        Regions.testing(player -> this.owned.get(), true);
        List<String> failed = new ArrayList<>();
        MiniMessageFlow.Builder builder = MiniMessageFlow.builder().errorHandler((key, error) -> failed.add(key));
        PaperPlaceholders.builder().applyTo(builder, () -> Fakes.SERVER);
        MiniMessageFlow flow = builder.build();
        String message = "<viewer_name> <viewer_x> <viewer_world> <server_max>";
        assertEquals("Steve <viewer_x> <viewer_world> 100", PLAIN.serialize(flow.format(message, Placeholder.empty(), this.steve)));
        assertTrue(failed.contains("viewer_x") && failed.contains("viewer_world"), failed.toString());
        this.owned.set(true);
        assertEquals("Steve 10 world_nether 100", PLAIN.serialize(flow.format(message, Placeholder.empty(), this.steve)));
    }

    @Test
    @DisplayName("Paper: world placeholders are read on any thread, as before")
    void paperUnchecked() {
        Regions.testing(player -> false, false);
        MiniMessageFlow.Builder builder = MiniMessageFlow.builder();
        PaperPlaceholders.builder().applyTo(builder, () -> Fakes.SERVER);
        assertEquals("10", PLAIN.serialize(builder.build().format("<viewer_x>", Placeholder.empty(), this.steve)));
    }

    @Test
    @DisplayName("PlaceholderAPI's mainThreadOnly asks the thread that owns the player")
    void placeholderApiOwner() {
        PlaceholderApi.Hook hook = new PlaceholderApi.Hook() {
            @Override
            public boolean isRegistered(@NotNull String identifier) {
                return true;
            }

            @Override
            public @NotNull String setPlaceholders(@Nullable OfflinePlayer player, @NotNull String text) {
                return "value";
            }
        };
        MiniMessageFlow.Builder builder = MiniMessageFlow.builder();
        PlaceholderApi.builder().mainThreadOnly(true)
                .applyTo(builder, hook, player -> player == this.steve && this.owned.get(), Fakes.recordingLogger(new ArrayList<>()));
        MiniMessageFlow flow = builder.build();
        assertEquals("<papi:x_y>", PLAIN.serialize(flow.format("<papi:x_y>", Placeholder.empty(), this.steve)));
        this.owned.set(true);
        assertEquals("value", PLAIN.serialize(flow.format("<papi:x_y>", Placeholder.empty(), this.steve)));
    }
}
