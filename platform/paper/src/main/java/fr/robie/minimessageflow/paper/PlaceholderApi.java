package fr.robie.minimessageflow.paper;

import fr.robie.minimessageflow.MiniMessageFlow;
import fr.robie.minimessageflow.ModifierSet;
import fr.robie.minimessageflow.Resolver;
import fr.robie.minimessageflow.cache.Cache;
import fr.robie.minimessageflow.cache.CacheFactory;
import fr.robie.minimessageflow.core.BoundedCache;
import net.kyori.adventure.pointer.Pointered;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import java.util.logging.Logger;

/**
 * PlaceholderAPI in MiniMessageFlow, when it is installed:
 * <ul>
 *   <li>{@code <papi:vault_eco_balance>} and {@code %papi:vault_eco_balance%}: the same placeholder, always;</li>
 *   <li>{@code %vault_eco_balance%} as written for PlaceholderAPI, with {@link Keys#ALL} (the default): any key
 *   {@code identifier_params} whose identifier is a registered expansion.</li>
 * </ul>
 * Modifiers apply to both ({@code <papi:vault_eco_balance:comma>}, {@code %vault_eco_balance.comma%}). The player is
 * the render's viewer. Text from PlaceholderAPI is formatted: its legacy colour codes are read, on their own (they end
 * with it). A placeholder PlaceholderAPI does not know is shown as written.
 * <pre>{@code
 * PlaceholderApi.builder().keys(PlaceholderApi.Keys.PREFIXED_ONLY).applyTo(flowBuilder, plugin);
 * }</pre>
 */
public final class PlaceholderApi {

    /**
     * Which keys go to PlaceholderAPI.
     */
    public enum Keys {
        /**
         * Only {@code <papi:...>} and {@code %papi:...%}: no other key ever reaches PlaceholderAPI.
         */
        PREFIXED_ONLY,
        /**
         * Also any {@code %identifier_params%} whose identifier is a registered expansion, as messages written for
         * PlaceholderAPI have them.
         */
        ALL
    }

    /**
     * What PlaceholderAPI does for this class; another one in tests.
     */
    interface Hook {
        boolean isRegistered(@NotNull String identifier);

        @NotNull String setPlaceholders(@Nullable OfflinePlayer player, @NotNull String text);
    }

    private PlaceholderApi() {
    }

    /**
     * Whether PlaceholderAPI is installed and enabled.
     */
    public static boolean isInstalled() {
        return Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI");
    }

    public static @NotNull Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private Keys keys = Keys.ALL;
        private String tagName = "papi";
        private boolean mainThreadOnly;
        private @Nullable java.util.function.Supplier<@Nullable Duration> cacheDuration;
        private int cacheSize = 10_000;
        private CacheFactory cacheFactory = BOUNDED;

        private Builder() {
        }

        /**
         * Keeps what PlaceholderAPI gives, per player and placeholder, for a while: within that time the same
         * placeholder for the same player is asked once, however many renders show it. Off by default (asked at each
         * render). A value is kept at most this long: the time is cut in windows of this length, and a new window asks
         * again. A placeholder PlaceholderAPI does not know is kept too (as unknown).
         *
         * @param duration how long a value is kept; zero or negative turns the cache off
         */
        public @NotNull Builder cache(@NotNull Duration duration) {
            Objects.requireNonNull(duration, "duration cannot be null");
            this.cacheDuration = duration.isZero() || duration.isNegative() ? null : () -> duration;
            return this;
        }

        /**
         * As {@link #cache(Duration)}, the duration read at each value asked: from a configuration reloaded, it applies
         * at once. A null, zero or negative duration asks PlaceholderAPI without the cache, while it is so.
         *
         * @param duration gives how long a value is kept
         */
        public @NotNull Builder cache(@NotNull java.util.function.Supplier<@Nullable Duration> duration) {
            this.cacheDuration = Objects.requireNonNull(duration, "duration cannot be null");
            return this;
        }

        /**
         * How many values the cache keeps at most ({@link #cache}): 10,000 by default.
         */
        public @NotNull Builder cacheSize(int cacheSize) {
            if (cacheSize < 1) {
                throw new IllegalArgumentException("cacheSize must be at least 1: " + cacheSize);
            }
            this.cacheSize = cacheSize;
            return this;
        }

        /**
         * Makes the cache ({@link #cache}), as {@code MiniMessageFlow.Builder.cacheFactory} makes the instance's
         * (Guava with {@link GuavaCacheFactory}...), with {@link CacheFactory.Kind#PLACEHOLDER_VALUES}. A bounded map by
         * default.
         */
        public @NotNull Builder cacheFactory(@NotNull CacheFactory cacheFactory) {
            this.cacheFactory = Objects.requireNonNull(cacheFactory, "cacheFactory cannot be null");
            return this;
        }

        /**
         * Which keys go to PlaceholderAPI. {@link Keys#ALL} by default.
         */
        public @NotNull Builder keys(@NotNull Keys keys) {
            this.keys = Objects.requireNonNull(keys, "keys cannot be null");
            return this;
        }

        /**
         * The tag's name: {@code papi} by default ({@code <papi:...>}, {@code %papi:...%}).
         */
        public @NotNull Builder tagName(@NotNull String tagName) {
            this.tagName = Objects.requireNonNull(tagName, "tagName cannot be null");
            return this;
        }

        /**
         * Whether PlaceholderAPI is only asked on the thread that owns the player: the main thread on Paper, the
         * player's region on Folia (the main thread for a render without a player). Many expansions read the world or
         * call other plugins, which is not thread-safe. With true, a render on another thread (an async chat event, a
         * task) shows the placeholder as written, and the plugin's logger warns once. False by default:
         * PlaceholderAPI is asked on any thread, as most plugins do.
         */
        public @NotNull Builder mainThreadOnly(boolean mainThreadOnly) {
            this.mainThreadOnly = mainThreadOnly;
            return this;
        }

        /**
         * Adds PlaceholderAPI to a builder when it is installed and enabled; does nothing otherwise.
         *
         * @return whether PlaceholderAPI was added
         */
        public boolean applyTo(@NotNull MiniMessageFlow.Builder flow, @NotNull Plugin plugin) {
            Objects.requireNonNull(flow, "flow cannot be null");
            Objects.requireNonNull(plugin, "plugin cannot be null");
            if (!isInstalled()) {
                return false;
            }
            this.applyTo(flow, new ClipHook(), player -> player != null ? Regions.ownsPlayer(player) : Bukkit.isPrimaryThread(),
                    plugin.getLogger());
            return true;
        }

        /**
         * Adds PlaceholderAPI with a given hook (for tests).
         */
        void applyTo(@NotNull MiniMessageFlow.Builder flow, @NotNull Hook hook, @NotNull Predicate<@Nullable Player> owner,
                     @NotNull Logger logger) {
            Cache<CacheKey, String> cache = this.cacheDuration == null ? null
                    : this.cacheFactory.create(CacheFactory.Kind.PLACEHOLDER_VALUES, this.cacheSize);
            Asker asker = new Asker(hook, this.mainThreadOnly ? owner : null, logger, cache, this.cacheDuration);
            flow.valueTag(this.tagName, (arguments, viewer) -> asker.prefixed(arguments, viewer));
            if (this.keys == Keys.ALL) {
                flow.resolver(asker);
            }
        }
    }

    /**
     * Asks PlaceholderAPI for a {@code <papi:...>} tag's value, and, as a resolver, for any registered key.
     */
    private static final class Asker implements Resolver {
        private final Hook hook;
        /**
         * Whether this thread owns a player (null for none), when PlaceholderAPI is only asked there.
         */
        private final @Nullable Predicate<@Nullable Player> owner;
        private final Logger logger;
        private final AtomicBoolean warned = new AtomicBoolean();
        /**
         * Read once per key (the keys come from messages: the same few come again): whether a key with a dot has no
         * modifiers, and the identifier of a key.
         */
        private final BoundedCache<String, Boolean> withoutModifiers = new BoundedCache<>(256);
        private final BoundedCache<String, String> identifiers = new BoundedCache<>(256);
        /**
         * The text PlaceholderAPI is given for each placeholder ({@code %vault_eco_balance%}), made once.
         */
        private final BoundedCache<String, String> tokens = new BoundedCache<>(256);
        /**
         * PlaceholderAPI's values by player, placeholder and time window, or null without a cache.
         */
        private final @Nullable Cache<CacheKey, String> cache;
        /**
         * How long a value is kept, read at each value asked; null without a cache.
         */
        private final @Nullable java.util.function.Supplier<@Nullable Duration> duration;

        Asker(@NotNull Hook hook, @Nullable Predicate<@Nullable Player> owner, @NotNull Logger logger,
              @Nullable Cache<CacheKey, String> cache, @Nullable java.util.function.Supplier<@Nullable Duration> duration) {
            this.hook = hook;
            this.owner = owner;
            this.logger = logger;
            this.cache = cache;
            this.duration = duration;
        }

        /**
         * The key of a value in the cache for now, or null when the cache is off (no cache, or no duration now).
         */
        private @Nullable CacheKey key(@NotNull String placeholder, @Nullable Player player) {
            if (this.cache == null || this.duration == null) {
                return null;
            }
            Duration duration = this.duration.get();
            if (duration == null || duration.isZero() || duration.isNegative()) {
                return null;
            }
            long length = Math.max(1, duration.toNanos());
            return new CacheKey(player == null ? null : player.getUniqueId(), placeholder, length, System.nanoTime() / length);
        }

        /**
         * {@code <papi:a:b>}: the arguments are one placeholder, a {@code :} inside it splitting it into several.
         */
        @Nullable String prefixed(@NotNull List<String> arguments, @Nullable Pointered viewer) {
            if (arguments.isEmpty()) {
                return null;
            }
            String placeholder = arguments.size() == 1 ? arguments.getFirst() : String.join(":", arguments);
            return this.ask(placeholder, viewer);
        }

        /**
         * A bare key: PlaceholderAPI's only when its identifier (before the first {@code _}) is a registered
         * expansion, so other keys never cost a call.
         */
        @Override
        public @Nullable String resolve(@NotNull String key, @Nullable Pointered viewer) {
            if (!this.isApiKey(key)) {
                return null;
            }
            String identifier = this.identifiers.get(key, k -> k.substring(0, k.indexOf('_')).toLowerCase(Locale.ROOT));
            return this.hook.isRegistered(identifier) ? this.ask(key, viewer) : null;
        }

        /**
         * Whether a key is written {@code identifier_params} with nothing in the identifier PlaceholderAPI can't have
         * ({@code papi:x} is the tag's), and without modifiers after a dot ({@code vault_eco_balance.comma}: the
         * engine then asks again without them and applies them; asked as is, an expansion might give an empty text
         * for parameters it does not know, and the modifiers would be lost). Read once per key.
         */
        private boolean isApiKey(@NotNull String key) {
            int underscore = key.indexOf('_');
            if (underscore <= 0 || underscore == key.length() - 1) {
                return false;
            }
            for (int i = 0; i < underscore; i++) {
                char c = key.charAt(i);
                if (c == ':' || c == '.') {
                    return false;
                }
            }
            return key.indexOf('.') < 0 || this.withoutModifiers.get(key, Asker::withoutModifiers);
        }

        private static boolean withoutModifiers(@NotNull String key) {
            for (int dot = key.indexOf('.'); dot >= 0; dot = key.indexOf('.', dot + 1)) {
                if (ModifierSet.shared().compile(key.substring(dot + 1)) != null) {
                    return false;
                }
            }
            return true;
        }

        private @Nullable String ask(@NotNull String placeholder, @Nullable Pointered viewer) {
            Player player = Viewers.player(viewer);
            CacheKey key = this.key(placeholder, player);
            if (this.owner != null && !this.owner.test(player)) {
                // Off the owning thread: a value kept for this window is still given.
                String kept = key == null ? null : this.cache.getIfPresent(key);
                if (kept != null) {
                    return kept == UNKNOWN ? null : kept;
                }
                if (this.warned.compareAndSet(false, true)) {
                    this.logger.warning("A message with a PlaceholderAPI placeholder (%" + placeholder
                            + "%) was rendered off the thread that owns the player: shown as written (mainThreadOnly)."
                            + " Warned once.");
                }
                return null;
            }
            if (key == null) {
                return this.askHook(placeholder, player);
            }
            String value = this.cache.get(key, k -> {
                String asked = this.askHook(placeholder, player);
                return asked == null ? UNKNOWN : asked;
            });
            return value == UNKNOWN ? null : value;
        }

        private @Nullable String askHook(@NotNull String placeholder, @Nullable Player player) {
            String token = this.tokens.get(placeholder, p -> "%" + p + "%");
            String result = this.hook.setPlaceholders(player, token);
            // PlaceholderAPI gives a placeholder it does not know back as it is.
            return token.equals(result) ? null : result;
        }
    }

    /**
     * Kept for a placeholder PlaceholderAPI does not know: compared by identity.
     */
    @SuppressWarnings("StringOperationCanBeSimplified")
    private static final String UNKNOWN = new String("");

    /**
     * A cached value's key: the player (null without one), the placeholder, the windows' length (a changed duration
     * never reads another length's windows) and the time window it was asked in.
     */
    record CacheKey(@Nullable java.util.UUID player, @NotNull String placeholder, long length, long window) {
    }

    /**
     * The cache without a factory given: a bounded map, as the instance's default.
     */
    private static final CacheFactory BOUNDED = new CacheFactory() {
        @Override
        public <K, V> @NotNull Cache<K, V> create(@NotNull Kind kind, int maximumSize) {
            return new BoundedCache<>(maximumSize);
        }
    };
}
