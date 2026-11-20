package fr.robie.minimessageflow.paper;

import fr.robie.minimessageflow.MiniMessageFlow;
import fr.robie.minimessageflow.Placeholder;
import fr.robie.minimessageflow.cache.Cache;
import fr.robie.minimessageflow.cache.CacheFactory;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class GuavaCacheFactoryTest {

    @Test
    @DisplayName("Loads once, then gives the cached value; clear drops it")
    void cache() {
        Cache<String, String> cache = GuavaCacheFactory.create().create(CacheFactory.Kind.TEMPLATES, 10);
        AtomicInteger loads = new AtomicInteger();
        assertNull(cache.getIfPresent("a"));
        assertEquals("A", cache.get("a", key -> { loads.incrementAndGet(); return key.toUpperCase(); }));
        assertEquals("A", cache.get("a", key -> { loads.incrementAndGet(); return "other"; }));
        assertEquals(1, loads.get());
        cache.clear();
        assertNull(cache.getIfPresent("a"));
    }

    @Test
    @DisplayName("A loader may read the same cache (no recursive load error)")
    void reentrant() {
        Cache<String, String> cache = GuavaCacheFactory.create().create(CacheFactory.Kind.TEMPLATES, 10);
        assertEquals("ab", cache.get("ab", key -> cache.get("a", k -> "a") + "b"));
    }

    @Test
    @DisplayName("Bounded by the size MiniMessageFlow gives")
    void bounded() {
        Cache<Integer, Integer> cache = GuavaCacheFactory.create().create(CacheFactory.Kind.TEMPLATES, 5);
        for (int i = 0; i < 100; i++) {
            cache.get(i, key -> key);
        }
        int kept = 0;
        for (int i = 0; i < 100; i++) {
            if (cache.getIfPresent(i) != null) kept++;
        }
        assertTrue(kept <= 5, "kept " + kept);
    }

    @Test
    @DisplayName("Works as an instance's caches; expireAfterAccess must be positive")
    void inAnInstance() {
        MiniMessageFlow flow = MiniMessageFlow.builder()
                .cacheFactory(GuavaCacheFactory.builder().expireAfterAccess(Duration.ofMinutes(10)).build())
                .build();
        assertEquals("Hello Steve", PlainTextComponentSerializer.plainText().serialize(
                flow.format("<green>Hello %name%", Placeholder.of("name", "Steve"))));
        assertEquals("/pay Steve", flow.formatText("/pay %name%", Placeholder.of("name", "Steve")));
        assertThrows(IllegalArgumentException.class, () -> GuavaCacheFactory.builder().expireAfterAccess(Duration.ZERO));
    }
}
