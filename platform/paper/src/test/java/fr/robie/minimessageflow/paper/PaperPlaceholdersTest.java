package fr.robie.minimessageflow.paper;

import fr.robie.minimessageflow.MiniMessageFlow;
import fr.robie.minimessageflow.Placeholder;
import fr.robie.minimessageflow.Template;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class PaperPlaceholdersTest {

    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();
    private final UUID uuid = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private final Player steve = Fakes.player("Steve", this.uuid);

    @AfterEach
    void offline() {
        Fakes.ONLINE.clear();
    }

    private static MiniMessageFlow flow(PaperPlaceholders.Builder placeholders) {
        MiniMessageFlow.Builder flow = MiniMessageFlow.builder();
        placeholders.applyTo(flow, () -> Fakes.SERVER);
        return flow.build();
    }

    private String plain(MiniMessageFlow flow, String message) {
        return PLAIN.serialize(flow.format(message, Placeholder.empty(), this.steve));
    }

    @Test
    @DisplayName("The built-in placeholders, as %key% and as <key>")
    void builtIns() {
        Fakes.ONLINE.add(this.steve);
        MiniMessageFlow flow = flow(PaperPlaceholders.builder());
        assertEquals("Steve [Steve] 00000000-0000-0000-0000-000000000001 world_nether 42 fr_FR 10 64 -4",
                this.plain(flow, "%viewer_name% %viewer_displayname% %viewer_uuid% %viewer_world% %viewer_ping%"
                        + " %viewer_locale% %viewer_x% %viewer_y% %viewer_z%"));
        assertEquals("Steve 1 100 19.99 12.35",
                this.plain(flow, "<viewer_name> <server_online> <server_max> <server_tps> <server_mspt>"));
    }

    @Test
    @DisplayName("A component keeps its style; text is plain text, never read as tags or colour codes")
    void values() {
        MiniMessageFlow flow = flow(PaperPlaceholders.builder().player("motto", player -> "&c<b>not red"));
        Component rendered = flow.format("<viewer_displayname> <viewer_motto>", Placeholder.empty(), this.steve).compact();
        assertEquals("[Steve] &c<b>not red", PLAIN.serialize(rendered));
        assertEquals(NamedTextColor.GOLD, rendered.children().isEmpty() ? rendered.color() : rendered.children().getFirst().color());
    }

    @Test
    @DisplayName("Modifiers apply; without a viewer a viewer placeholder is shown as written")
    void modifiersAndNoViewer() {
        MiniMessageFlow flow = flow(PaperPlaceholders.builder());
        assertEquals("STEVE 20.0 STEVE", this.plain(flow, "<viewer_name:upper> <server_tps:1f> %viewer_name.upper%"));
        assertEquals("%viewer_name% <viewer_name> 100", PLAIN.serialize(flow.format("%viewer_name% <viewer_name> <server_max>")));
    }

    @Test
    @DisplayName("Your own placeholders, with arguments, another prefix, and a built-in removed")
    void custom() {
        MiniMessageFlow flow = flow(PaperPlaceholders.builder()
                .playerWithArguments("stat", (player, arguments) -> player.getName() + ":" + String.join(",", arguments))
                .server("motd", server -> Component.text("Welcome"))
                .playerPrefix("me")
                .withoutServer("tps"));
        assertEquals("Steve:jump,walk Welcome Steve <server_tps>",
                this.plain(flow, "<me_stat:jump:walk> <server_motd> %me_name% <server_tps>"));
    }

    @Test
    @DisplayName("A message with them keeps the fast path, and changes with the viewer")
    void fastPath() {
        MiniMessageFlow flow = flow(PaperPlaceholders.builder());
        Template template = flow.compile("<green>Hi <viewer_name>, <server_online> online");
        assertEquals(Template.Kind.NATIVE, template.kind());
        Player alex = Fakes.player("Alex", UUID.randomUUID());
        assertEquals("Hi Steve, 0 online", PLAIN.serialize(template.render(Placeholder.empty(), this.steve)));
        assertEquals("Hi Alex, 0 online", PLAIN.serialize(template.render(Placeholder.empty(), alex)));
    }

    @Test
    @DisplayName("A name a placeholder can't have is refused")
    void names() {
        assertThrows(IllegalArgumentException.class, () -> PaperPlaceholders.builder().player("Level", Player::getLevel));
        assertThrows(IllegalArgumentException.class, () -> PaperPlaceholders.builder().playerPrefix("my prefix"));
    }
}
