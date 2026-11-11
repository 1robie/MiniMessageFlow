package fr.robie.minimessageflow.cache;

import org.jetbrains.annotations.NotNull;

/**
 * Makes the caches of a {@code MiniMessageFlow}, so a platform can use its own (Guava, Caffeine, with expiry...).
 * The default keeps the library free of dependencies: a bounded map that drops part of its entries when full.
 */
@FunctionalInterface
public interface CacheFactory {

    /**
     * What each cache holds, to size or name it.
     */
    enum Kind {
        /**
         * Texts given to {@code format}, compiled ({@code templateCacheSize}).
         */
        TEMPLATES,
        /**
         * Formatted text of resolvers, parsed ({@code valueCacheSize}).
         */
        RESOLVER_TEXTS,
        /**
         * Messages parsed with their values when they can't be compiled ahead ({@code valueCacheSize}).
         */
        PARSED_WITH_VALUES,
        /**
         * Texts with values written into tag arguments, compiled ({@code valueCacheSize}).
         */
        INLINE_TEXTS
    }

    /**
     * @param kind        what the cache holds
     * @param maximumSize the size set on the builder for this kind; a cache may hold more briefly, never far more
     * @return a new, empty, thread-safe cache
     */
    <K, V> @NotNull Cache<K, V> create(@NotNull Kind kind, int maximumSize);
}
