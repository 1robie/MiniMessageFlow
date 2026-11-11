package fr.robie.minimessageflow.core;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Iterator;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * A thread-safe cache that never holds more than about {@code maximumSize} entries. When it is full, a quarter of
 * the entries are dropped, oldest first in practice but not exactly (no access order is kept, so a hit costs one
 * {@code ConcurrentHashMap} lookup and nothing else).
 */
@ApiStatus.Internal
public final class BoundedCache<K, V> implements fr.robie.minimessageflow.cache.Cache<K, V> {

    private final int maximumSize;
    private final ConcurrentHashMap<K, V> map;

    public BoundedCache(int maximumSize) {
        if (maximumSize < 1) {
            throw new IllegalArgumentException("maximumSize must be at least 1: " + maximumSize);
        }
        this.maximumSize = maximumSize;
        this.map = new ConcurrentHashMap<>(Math.min(maximumSize, 64));
    }

    @Override
    public @Nullable V getIfPresent(@NotNull K key) {
        return this.map.get(key);
    }

    /**
     * The cached value, or the one {@code loader} gives, cached. The loader runs outside any lock: two threads may
     * both load the same key, and one result is kept.
     */
    @Override
    public @NotNull V get(@NotNull K key, @NotNull Function<? super K, ? extends V> loader) {
        V value = this.map.get(key);
        if (value != null) {
            return value;
        }
        V loaded = loader.apply(key);
        if (this.map.size() >= this.maximumSize) {
            this.evict();
        }
        V previous = this.map.putIfAbsent(key, loaded);
        return previous != null ? previous : loaded;
    }

    private void evict() {
        int target = this.maximumSize - Math.max(1, this.maximumSize / 4);
        Iterator<K> keys = this.map.keySet().iterator();
        while (this.map.size() > target && keys.hasNext()) {
            keys.next();
            keys.remove();
        }
    }

    public int size() {
        return this.map.size();
    }

    @Override
    public void clear() {
        this.map.clear();
    }
}
