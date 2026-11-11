package fr.robie.minimessageflow.cache;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.function.Function;

/**
 * A cache MiniMessageFlow keeps compiled texts and values in. Must be thread-safe. Keys and values are immutable;
 * values are never null.
 *
 * @param <K> the key
 * @param <V> the value
 */
public interface Cache<K, V> {

    /**
     * The cached value, or null.
     */
    @Nullable V getIfPresent(@NotNull K key);

    /**
     * The cached value, or the one {@code loader} gives, cached. The loader may run more than once for a key under
     * concurrent calls: it only builds immutable values.
     */
    @NotNull V get(@NotNull K key, @NotNull Function<? super K, ? extends V> loader);

    /**
     * Drops every entry.
     */
    void clear();
}
