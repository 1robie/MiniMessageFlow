package fr.robie.minimessageflow.paper;

import com.google.common.cache.CacheBuilder;
import fr.robie.minimessageflow.cache.Cache;
import fr.robie.minimessageflow.cache.CacheFactory;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Caches made with Guava, which every Paper server has: bounded by their size, least recently used dropped first,
 * and, if set, an entry not read for a while dropped too.
 * <pre>{@code
 * MiniMessageFlow.builder().cacheFactory(GuavaCacheFactory.create());
 * MiniMessageFlow.builder().cacheFactory(GuavaCacheFactory.builder().expireAfterAccess(Duration.ofMinutes(10)).build());
 * // From a configuration read again on reload: read when a cache is made and when it is cleared
 * MiniMessageFlow.builder().cacheFactory(GuavaCacheFactory.builder()
 *         .expireAfterAccess(() -> Duration.ofMinutes(config.getLong("cache.expire-minutes"))).build());
 * }</pre>
 * Opt-in: a cache read records the access order, about 30 ns and 24 B more than the default caches, so
 * {@link PaperFlow} keeps the default. Worth it for many different texts, or for the expiry.
 */
public final class GuavaCacheFactory implements CacheFactory {

    private final @Nullable Supplier<@Nullable Duration> expireAfterAccess;

    private GuavaCacheFactory(@Nullable Supplier<@Nullable Duration> expireAfterAccess) {
        this.expireAfterAccess = expireAfterAccess;
    }

    /**
     * Caches bounded by their size only.
     */
    public static @NotNull GuavaCacheFactory create() {
        return new GuavaCacheFactory(null);
    }

    public static @NotNull Builder builder() {
        return new Builder();
    }

    @Override
    public <K, V> @NotNull Cache<K, V> create(@NotNull Kind kind, int maximumSize) {
        return new GuavaCache<>(maximumSize, this.expireAfterAccess);
    }

    private static <K, V> com.google.common.cache.@NotNull Cache<K, V> build(int maximumSize,
                                                                             @Nullable Supplier<@Nullable Duration> expireAfterAccess) {
        CacheBuilder<Object, Object> builder = CacheBuilder.newBuilder().maximumSize(maximumSize);
        Duration expire = expireAfterAccess == null ? null : expireAfterAccess.get();
        if (expire != null && !expire.isNegative() && !expire.isZero()) {
            builder.expireAfterAccess(expire);
        }
        return builder.build();
    }

    /**
     * A Guava cache as MiniMessageFlow's. A value is loaded outside Guava's lock: a loader may compile something that
     * reads the same cache, which Guava's own loading would refuse, and the loader only builds immutable values, so
     * two threads loading the same key at once is harmless.
     */
    private static final class GuavaCache<K, V> implements Cache<K, V> {
        private final int maximumSize;
        private final @Nullable Supplier<@Nullable Duration> expireAfterAccess;
        /**
         * Made with the expiry read now; made again by {@link #clear()}, reading it again.
         */
        private volatile com.google.common.cache.Cache<K, V> cache;

        GuavaCache(int maximumSize, @Nullable Supplier<@Nullable Duration> expireAfterAccess) {
            this.maximumSize = maximumSize;
            this.expireAfterAccess = expireAfterAccess;
            this.cache = build(maximumSize, expireAfterAccess);
        }

        @Override
        public @Nullable V getIfPresent(@NotNull K key) {
            return this.cache.getIfPresent(key);
        }

        @Override
        public @NotNull V get(@NotNull K key, @NotNull Function<? super K, ? extends V> loader) {
            V value = this.cache.getIfPresent(key);
            if (value == null) {
                value = Objects.requireNonNull(loader.apply(key), "a cache loader gave null");
                this.cache.put(key, value);
            }
            return value;
        }

        @Override
        public void clear() {
            com.google.common.cache.Cache<K, V> old = this.cache;
            this.cache = this.expireAfterAccess == null ? old : build(this.maximumSize, this.expireAfterAccess);
            old.invalidateAll();
        }
    }

    public static final class Builder {
        private @Nullable Supplier<@Nullable Duration> expireAfterAccess;

        private Builder() {
        }

        /**
         * Drops an entry not read for this long, besides the size bound: a message sent once a day does not stay.
         */
        public @NotNull Builder expireAfterAccess(@NotNull Duration expireAfterAccess) {
            Objects.requireNonNull(expireAfterAccess, "expireAfterAccess cannot be null");
            if (expireAfterAccess.isNegative() || expireAfterAccess.isZero()) {
                throw new IllegalArgumentException("expireAfterAccess must be positive: " + expireAfterAccess);
            }
            this.expireAfterAccess = () -> expireAfterAccess;
            return this;
        }

        /**
         * Drops an entry not read for the duration the supplier gives, read when a cache is made and each time it is
         * cleared ({@code MiniMessageFlow.clearCache()} on a reload): a configured expiry followed on reload. Null,
         * zero or negative: no expiry.
         */
        public @NotNull Builder expireAfterAccess(@NotNull Supplier<@Nullable Duration> expireAfterAccess) {
            this.expireAfterAccess = Objects.requireNonNull(expireAfterAccess, "expireAfterAccess cannot be null");
            return this;
        }

        public @NotNull GuavaCacheFactory build() {
            return new GuavaCacheFactory(this.expireAfterAccess);
        }
    }
}
