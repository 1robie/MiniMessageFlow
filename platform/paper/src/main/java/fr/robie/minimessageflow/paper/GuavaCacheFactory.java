package fr.robie.minimessageflow.paper;

import com.google.common.cache.CacheBuilder;
import fr.robie.minimessageflow.cache.Cache;
import fr.robie.minimessageflow.cache.CacheFactory;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.util.Objects;
import java.util.function.Function;

/**
 * Caches made with Guava, which every Paper server has: bounded by their size, least recently used dropped first,
 * and, if set, an entry not read for a while dropped too.
 * <pre>{@code
 * MiniMessageFlow.builder().cacheFactory(GuavaCacheFactory.create());
 * MiniMessageFlow.builder().cacheFactory(GuavaCacheFactory.builder().expireAfterAccess(Duration.ofMinutes(10)).build());
 * }</pre>
 * Opt-in: a cache read records the access order, about 30 ns and 24 B more than the default caches, so
 * {@link PaperFlow} keeps the default. Worth it for many different texts, or for the expiry.
 */
public final class GuavaCacheFactory implements CacheFactory {

    private final @Nullable Duration expireAfterAccess;

    private GuavaCacheFactory(@Nullable Duration expireAfterAccess) {
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
        CacheBuilder<Object, Object> builder = CacheBuilder.newBuilder().maximumSize(maximumSize);
        if (this.expireAfterAccess != null) {
            builder.expireAfterAccess(this.expireAfterAccess);
        }
        return new GuavaCache<>(builder.build());
    }

    /**
     * A Guava cache as MiniMessageFlow's. A value is loaded outside Guava's lock: a loader may compile something that
     * reads the same cache, which Guava's own loading would refuse, and the loader only builds immutable values, so
     * two threads loading the same key at once is harmless.
     */
    private record GuavaCache<K, V>(@NotNull com.google.common.cache.Cache<K, V> cache) implements Cache<K, V> {

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
            this.cache.invalidateAll();
        }
    }

    public static final class Builder {
        private @Nullable Duration expireAfterAccess;

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
            this.expireAfterAccess = expireAfterAccess;
            return this;
        }

        public @NotNull GuavaCacheFactory build() {
            return new GuavaCacheFactory(this.expireAfterAccess);
        }
    }
}
