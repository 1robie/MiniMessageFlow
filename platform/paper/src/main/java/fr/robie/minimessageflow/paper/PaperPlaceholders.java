package fr.robie.minimessageflow.paper;

import fr.robie.minimessageflow.MiniMessageFlow;
import fr.robie.minimessageflow.ValueTag;
import net.kyori.adventure.pointer.Pointered;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.ComponentLike;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
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
 *   <tr><td>{@code viewer_item}, {@code viewer_item:offhand} (or {@code head}, {@code chest}, {@code legs},
 *   {@code feet})</td><td>the item's name as a player sees it, a component</td></tr>
 *   <tr><td>{@code viewer_item_display}, with the same slots</td><td>the item as chat shows it: its name in brackets,
 *   its tooltip on hover</td></tr>
 *   <tr><td>{@code viewer_target}, {@code viewer_target:20}</td><td>the name of the entity the viewer looks at, within
 *   10 blocks (or the distance given, at most 100); shown as written when there is none</td></tr>
 *   <tr><td>{@code viewer_vehicle}</td><td>the name of the entity the viewer rides</td></tr>
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
 * <p>
 * {@code viewer_item...}, {@code viewer_target}, {@code viewer_vehicle}, {@code viewer_world} and the location read
 * the world: render a message with them where the server allows it. On Paper they are read on any thread, as a
 * plugin's own code would; on Folia only on the thread of the player's region: from another, the placeholder is
 * shown as written and the error handler is told.
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
            this.player("world", world(player -> player.getWorld().getName()));
            this.player("ping", Player::getPing);
            this.player("locale", player -> player.locale().toString());
            this.player("x", world(player -> block(player).getBlockX()));
            this.player("y", world(player -> block(player).getBlockY()));
            this.player("z", world(player -> block(player).getBlockZ()));
            this.playerWithArguments("item", world((player, arguments) -> PaperBridge.CURRENT.itemName(item(player, arguments))));
            this.playerWithArguments("item_display", world((player, arguments) -> item(player, arguments).displayName()));
            this.playerWithArguments("target", world((player, arguments) -> {
                Entity target = player.getTargetEntity(distance(arguments));
                return target == null ? null : target.name();
            }));
            this.player("vehicle", world(player -> {
                Entity vehicle = player.getVehicle();
                return vehicle == null ? null : vehicle.name();
            }));
            this.server("online", server -> server.getOnlinePlayers().size());
            this.server("max", Server::getMaxPlayers);
            // Paper: the last minute's ticks per second, at most 20; the average milliseconds a tick takes. On Folia
            // each region ticks on its own: off a region's thread there is none (shown as written, nothing logged).
            this.server("tps", server -> onRegion(() -> decimal(Math.min(20, server.getTPS()[0]))));
            this.server("mspt", server -> onRegion(() -> decimal(server.getAverageTickTime())));
            return this;
        }

        /**
         * A value read from the player's world: on Folia, only from the thread of the player's region (from another,
         * the placeholder fails, so it is shown as written and the error handler is told once).
         */
        private static @NotNull Function<Player, ?> world(@NotNull Function<Player, ?> value) {
            return player -> {
                checkRegion(player);
                return value.apply(player);
            };
        }

        private static @NotNull BiFunction<Player, List<String>, ?> world(@NotNull BiFunction<Player, List<String>, ?> value) {
            return (player, arguments) -> {
                checkRegion(player);
                return value.apply(player, arguments);
            };
        }

        /**
         * A server value that Folia only has on a region's thread ("Not on any region" elsewhere): null there.
         */
        private static @Nullable String onRegion(@NotNull java.util.function.Supplier<String> value) {
            try {
                return value.get();
            } catch (UnsupportedOperationException e) {
                if (Regions.foliaRules()) {
                    return null;
                }
                throw e;
            }
        }

        private static void checkRegion(@NotNull Player player) {
            if (!Regions.mayReadWorld(player)) {
                throw new IllegalStateException("Folia: a placeholder reading " + player.getName()
                        + "'s world was rendered off the player's region thread; shown as written");
            }
        }

        private static @NotNull Location block(@NotNull Player player) {
            return player.getLocation();
        }

        /**
         * The item in a slot: the main hand without an argument, else {@code offhand}, {@code head}, {@code chest},
         * {@code legs} or {@code feet}.
         */
        private static @NotNull ItemStack item(@NotNull Player player, @NotNull List<String> arguments) {
            EquipmentSlot slot = arguments.isEmpty() ? EquipmentSlot.HAND : switch (arguments.getFirst().toLowerCase(Locale.ROOT)) {
                case "hand", "mainhand" -> EquipmentSlot.HAND;
                case "offhand" -> EquipmentSlot.OFF_HAND;
                case "head" -> EquipmentSlot.HEAD;
                case "chest" -> EquipmentSlot.CHEST;
                case "legs" -> EquipmentSlot.LEGS;
                case "feet" -> EquipmentSlot.FEET;
                default -> throw new IllegalArgumentException("no slot '" + arguments.getFirst()
                        + "': hand, offhand, head, chest, legs or feet");
            };
            return player.getInventory().getItem(slot);
        }

        /**
         * How far {@code viewer_target} looks: 10 blocks, or the argument, from 1 to 100.
         */
        private static int distance(@NotNull List<String> arguments) {
            if (arguments.isEmpty()) {
                return 10;
            }
            return Math.max(1, Math.min(100, Integer.parseInt(arguments.getFirst())));
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
