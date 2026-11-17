package fr.robie.minimessageflow.paper;

import fr.robie.minimessageflow.MiniMessageFlow;
import fr.robie.minimessageflow.ValueTag;
import net.kyori.adventure.pointer.Pointered;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.ComponentLike;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Placeholders of the viewer and of the server, with no other plugin: {@code %viewer_name%} and {@code <viewer_name>}
 * are the same, modifiers apply ({@code <server_tps:1f>}), and each is asked at each render.
 * <table>
 *   <caption>Built-in placeholders</caption>
 *   <tr><td>{@code viewer_name}, {@code viewer_uuid}, {@code viewer_world}, {@code viewer_ping}, {@code viewer_locale},
 *   {@code viewer_x}, {@code viewer_y}, {@code viewer_z}</td><td>text</td></tr>
 *   <tr><td>{@code viewer_displayname}</td><td>a component, with its own style</td></tr>
 *   <tr><td>{@code server_online}, {@code server_max}, {@code server_tps}, {@code server_mspt}</td><td>text</td></tr>
 * </table>
 * Add your own the same way, with or without arguments:
 * <pre>{@code
 * PaperPlaceholders.builder()
 *     .player("level", player -> player.getLevel())                      // %viewer_level%, <viewer_level>
 *     .playerWithArguments("stat", (p, args) -> ...)                      // <viewer_stat:jump>
 *     .server("motd", server -> server.motd())                           // <server_motd>, a component
 *     .applyTo(flowBuilder);
 * }</pre>
 * A value is a component when it is one ({@code ComponentLike}), else plain text ({@code String.valueOf}): never read
 * as MiniMessage or colour codes. Without a viewer, or when the viewer is not an online player, a player placeholder
 * is shown as written; so is any placeholder whose value is null.
 */
public final class PaperPlaceholders {

    private PaperPlaceholders() {
    }

    /**
     * A builder with the built-in placeholders.
     */
    public static @NotNull Builder builder() {
        return new Builder().defaults();
    }

    /**
     * A builder with no placeholder: only yours.
     */
    public static @NotNull Builder empty() {
        return new Builder();
    }

    public static final class Builder {
        private final Map<String, BiFunction<Player, List<String>, ?>> players = new LinkedHashMap<>();
        private final Map<String, BiFunction<Server, List<String>, ?>> servers = new LinkedHashMap<>();
        private String playerPrefix = "viewer";
        private String serverPrefix = "server";

        private Builder() {
        }

        private @NotNull Builder defaults() {
            this.player("name", Player::getName);
            this.player("displayname", Player::displayName);
            this.player("uuid", player -> player.getUniqueId().toString());
            this.player("world", player -> player.getWorld().getName());
            this.player("ping", Player::getPing);
            this.player("locale", player -> player.locale().toString());
            this.player("x", player -> block(player).getBlockX());
            this.player("y", player -> block(player).getBlockY());
            this.player("z", player -> block(player).getBlockZ());
            this.server("online", server -> server.getOnlinePlayers().size());
            this.server("max", Server::getMaxPlayers);
            // Paper: the last minute's ticks per second, at most 20; the average milliseconds a tick takes.
            this.server("tps", server -> decimal(Math.min(20, server.getTPS()[0])));
            this.server("mspt", server -> decimal(server.getAverageTickTime()));
            return this;
        }

        private static @NotNull Location block(@NotNull Player player) {
            return player.getLocation();
        }

        private static @NotNull String decimal(double value) {
            return String.format(Locale.ROOT, "%.2f", value);
        }

        /**
         * Adds (or replaces) a placeholder of the viewer: {@code %viewer_<name>%}, {@code <viewer_<name>>}.
         */
        public @NotNull Builder player(@NotNull String name, @NotNull Function<Player, ?> value) {
            Objects.requireNonNull(value, "value cannot be null");
            return this.playerWithArguments(name, (player, arguments) -> value.apply(player));
        }

        /**
         * Adds (or replaces) a placeholder of the viewer with arguments: {@code <viewer_<name>:a:b>}.
         */
        public @NotNull Builder playerWithArguments(@NotNull String name, @NotNull BiFunction<Player, List<String>, ?> value) {
            this.players.put(checkName(name), Objects.requireNonNull(value, "value cannot be null"));
            return this;
        }

        /**
         * Adds (or replaces) a placeholder of the server: {@code %server_<name>%}, {@code <server_<name>>}.
         */
        public @NotNull Builder server(@NotNull String name, @NotNull Function<Server, ?> value) {
            Objects.requireNonNull(value, "value cannot be null");
            return this.serverWithArguments(name, (server, arguments) -> value.apply(server));
        }

        /**
         * Adds (or replaces) a placeholder of the server with arguments: {@code <server_<name>:a:b>}.
         */
        public @NotNull Builder serverWithArguments(@NotNull String name, @NotNull BiFunction<Server, List<String>, ?> value) {
            this.servers.put(checkName(name), Objects.requireNonNull(value, "value cannot be null"));
            return this;
        }

        /**
         * Removes a placeholder of the viewer, built-in or not.
         */
        public @NotNull Builder withoutPlayer(@NotNull String name) {
            this.players.remove(name);
            return this;
        }

        /**
         * Removes a placeholder of the server, built-in or not.
         */
        public @NotNull Builder withoutServer(@NotNull String name) {
            this.servers.remove(name);
            return this;
        }

        /**
         * The first part of the viewer's placeholders: {@code viewer} by default ({@code %viewer_name%}). Another one
         * avoids a clash, for example with PlaceholderAPI's {@code %player_...%}.
         */
        public @NotNull Builder playerPrefix(@NotNull String prefix) {
            this.playerPrefix = checkName(prefix);
            return this;
        }

        /**
         * The first part of the server's placeholders: {@code server} by default ({@code %server_online%}).
         */
        public @NotNull Builder serverPrefix(@NotNull String prefix) {
            this.serverPrefix = checkName(prefix);
            return this;
        }

        /**
         * Adds every placeholder to a builder, as value tags ({@code builder.valueTag(...)}).
         */
        public void applyTo(@NotNull MiniMessageFlow.Builder flow) {
            this.applyTo(flow, Bukkit::getServer);
        }

        /**
         * As {@link #applyTo(MiniMessageFlow.Builder)}, with the server given (for tests).
         */
        void applyTo(@NotNull MiniMessageFlow.Builder flow, @NotNull java.util.function.Supplier<Server> server) {
            Objects.requireNonNull(flow, "flow cannot be null");
            this.players.forEach((name, value) -> flow.valueTag(this.playerPrefix + "_" + name,
                    new Plain((arguments, viewer) -> {
                        Player player = Viewers.player(viewer);
                        return player == null ? null : value.apply(player, arguments);
                    })));
            this.servers.forEach((name, value) -> flow.valueTag(this.serverPrefix + "_" + name,
                    new Plain((arguments, viewer) -> value.apply(server.get(), arguments))));
        }

        private static @NotNull String checkName(@NotNull String name) {
            Objects.requireNonNull(name, "name cannot be null");
            if (name.isEmpty() || !name.chars().allMatch(c -> c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '_' || c == '-')) {
                throw new IllegalArgumentException("a placeholder's name is lower-case letters, digits, '_' and '-': '" + name + "'");
            }
            return name;
        }
    }

    /**
     * A value as a value tag gives it: a component as it is, anything else as plain text (a component without
     * style, so its text is never read as MiniMessage or colour codes).
     */
    private record Plain(@NotNull BiFunction<List<String>, Pointered, ?> value) implements ValueTag {

        @Override
        public @Nullable String resolve(@NotNull List<String> arguments, @Nullable Pointered viewer) {
            return null; // every value is given by resolveComponent
        }

        @Override
        public @Nullable Component resolveComponent(@NotNull List<String> arguments, @Nullable Pointered viewer) {
            Object value = this.value.apply(arguments, viewer);
            if (value == null) {
                return null;
            }
            return value instanceof ComponentLike component ? component.asComponent() : Component.text(String.valueOf(value));
        }
    }
}
