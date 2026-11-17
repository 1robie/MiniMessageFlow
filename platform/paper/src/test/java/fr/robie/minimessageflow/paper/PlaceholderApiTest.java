package fr.robie.minimessageflow.paper;

import fr.robie.minimessageflow.MiniMessageFlow;
import fr.robie.minimessageflow.Placeholder;
import fr.robie.minimessageflow.Template;
import net.kyori.adventure.identity.Identity;
import net.kyori.adventure.pointer.Pointered;
import net.kyori.adventure.pointer.Pointers;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
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
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class PlaceholderApiTest {

    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    /**
     * PlaceholderAPI with the {@code vault} and {@code player} expansions: {@code vault_eco_balance} is 1234567,
     * {@code vault_rank} is coloured, {@code player_name} the player's; unknown parameters of a known expansion give an
     * empty text, as some expansions do; an unknown expansion stays as written.
     */
    private static final class FakeApi implements PlaceholderApi.Hook {
        final AtomicInteger calls = new AtomicInteger();
        final List<String> asked = new ArrayList<>();

        @Override
        public boolean isRegistered(@NotNull String identifier) {
            return identifier.equals("vault") || identifier.equals("player");
        }

        @Override
        public @NotNull String setPlaceholders(@Nullable OfflinePlayer player, @NotNull String text) {
            this.calls.incrementAndGet();
            this.asked.add(text);
            return switch (text) {
                case "%vault_eco_balance%" -> "1234567";
                case "%vault_rank%" -> "&cAdmin";
                case "%player_name%" -> player == null ? "" : player.getName();
                case "%server_time_HH:mm%" -> "12:30";
                default -> text.startsWith("%vault_") || text.startsWith("%player_") ? "" : text;
            };
        }
    }

    private final FakeApi api = new FakeApi();
    private final List<LogRecord> logs = new ArrayList<>();
    private final Logger logger = Fakes.recordingLogger(this.logs);
    private final Player steve = Fakes.player("Steve", UUID.randomUUID());

    @AfterEach
    void offline() {
        Fakes.ONLINE.clear();
    }

    private MiniMessageFlow flow(PlaceholderApi.Builder builder, boolean mainThread) {
        MiniMessageFlow.Builder flow = MiniMessageFlow.builder();
        builder.applyTo(flow, this.api, () -> mainThread, this.logger);
        return flow.build();
    }

    private String plain(MiniMessageFlow flow, String message, Pointered viewer) {
        return PLAIN.serialize(flow.format(message, Placeholder.empty(), viewer));
    }

    @Test
    @DisplayName("<papi:...> and %papi:...% ask PlaceholderAPI with the viewer; an unknown one shows as written")
    void prefixed() {
        MiniMessageFlow flow = this.flow(PlaceholderApi.builder(), true);
        assertEquals("Steve Steve", this.plain(flow, "<papi:player_name> %papi:player_name%", this.steve));
        assertEquals("12:30", this.plain(flow, "<papi:'server_time_HH:mm'>", this.steve), "a ':' in quotes is kept");
        assertEquals("<papi:other_thing>", this.plain(flow, "<papi:other_thing>", this.steve));
    }

    @Test
    @DisplayName("Keys.ALL: a bare key of a registered expansion; never an unregistered one")
    void bareKeys() {
        MiniMessageFlow flow = this.flow(PlaceholderApi.builder(), true);
        assertEquals("1234567 %unknown_key% %nounderscore%",
                this.plain(flow, "%vault_eco_balance% %unknown_key% %nounderscore%", this.steve));
        assertEquals(List.of("%vault_eco_balance%"), this.api.asked, "the others never reach PlaceholderAPI");
    }

    @Test
    @DisplayName("Keys.PREFIXED_ONLY: a bare key never reaches PlaceholderAPI")
    void prefixedOnly() {
        MiniMessageFlow flow = this.flow(PlaceholderApi.builder().keys(PlaceholderApi.Keys.PREFIXED_ONLY), true);
        assertEquals("%vault_eco_balance% 1234567", this.plain(flow, "%vault_eco_balance% <papi:vault_eco_balance>", null));
        assertEquals(1, this.api.calls.get());
    }

    @Test
    @DisplayName("Modifiers apply in every form, even when the expansion answers unknown parameters with empty text")
    void modifiers() {
        MiniMessageFlow flow = this.flow(PlaceholderApi.builder(), true);
        assertEquals("1,234,567 1,234,567 1,234,567",
                this.plain(flow, "<papi:vault_eco_balance:comma> %papi:vault_eco_balance.comma% %vault_eco_balance.comma%", null));
    }

    @Test
    @DisplayName("PlaceholderAPI's colour codes are read, and end with its value")
    void legacyCodes() {
        MiniMessageFlow flow = this.flow(PlaceholderApi.builder(), true);
        Component rendered = flow.format("<papi:vault_rank> after", Placeholder.empty(), null).compact();
        assertEquals("Admin after", PLAIN.serialize(rendered));
        assertEquals(NamedTextColor.RED, rendered.children().isEmpty() ? rendered.color() : rendered.children().getFirst().color());
        assertNotEquals(NamedTextColor.RED, rendered.children().getLast().color(), "the colour ends with the value");
    }

    @Test
    @DisplayName("A viewer that is not a Player is found by its UUID among online players")
    void viewerByUuid() {
        Fakes.ONLINE.add(this.steve);
        Pointers pointers = Pointers.builder().withStatic(Identity.UUID, this.steve.getUniqueId()).build();
        Pointered audience = new Pointered() {
            @Override
            public @NotNull Pointers pointers() {
                return pointers;
            }
        };
        assertEquals("Steve", this.plain(this.flow(PlaceholderApi.builder(), true), "<papi:player_name>", audience));
    }

    @Test
    @DisplayName("mainThreadOnly: off the main thread, shown as written and warned once; on it, asked")
    void mainThreadOnly() {
        MiniMessageFlow off = this.flow(PlaceholderApi.builder().mainThreadOnly(true), false);
        assertEquals("<papi:vault_eco_balance> %vault_eco_balance%",
                this.plain(off, "<papi:vault_eco_balance> %vault_eco_balance%", null));
        assertEquals(0, this.api.calls.get());
        assertEquals(1, this.logs.size());
        assertEquals("1234567", this.plain(this.flow(PlaceholderApi.builder().mainThreadOnly(true), true), "%vault_eco_balance%", null));
    }

    @Test
    @DisplayName("A template keeps the fast path and asks PlaceholderAPI once per render")
    void fastPath() {
        MiniMessageFlow flow = this.flow(PlaceholderApi.builder(), true);
        Template template = flow.compile("<green>Balance: <papi:vault_eco_balance:comma>");
        assertEquals(Template.Kind.NATIVE, template.kind());
        assertEquals(0, this.api.calls.get(), "never asked while compiling");
        template.render(Placeholder.empty(), this.steve);
        template.render(Placeholder.empty(), this.steve);
        assertEquals(2, this.api.calls.get());
    }

    @Test
    @DisplayName("Without PlaceholderAPI installed, applyTo adds nothing")
    void notInstalled() {
        AtomicBoolean added = new AtomicBoolean(true);
        MiniMessageFlow.Builder flow = MiniMessageFlow.builder();
        added.set(PlaceholderApi.builder().applyTo(flow, Fakes.plugin(this.logger)));
        assertFalse(added.get());
        assertEquals("<papi:vault_eco_balance>", PLAIN.serialize(flow.build().format("<papi:vault_eco_balance>")));
        assertTrue(Map.of().isEmpty());
    }
}
