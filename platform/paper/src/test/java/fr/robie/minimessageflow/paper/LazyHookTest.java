package fr.robie.minimessageflow.paper;

import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LazyHookTest {

    private static final class Upper implements PlaceholderApi.Hook {
        @Override
        public boolean isRegistered(@NotNull String identifier) {
            return true;
        }

        @Override
        public @NotNull String setPlaceholders(@Nullable OfflinePlayer player, @NotNull String text) {
            return text.toUpperCase();
        }
    }

    @Test
    void asksBukkitAtMostOnceATickUntilEnabled() throws InterruptedException {
        AtomicInteger asked = new AtomicInteger();
        AtomicBoolean enabled = new AtomicBoolean();
        PlaceholderApi.LazyHook hook = new PlaceholderApi.LazyHook(() -> {
            asked.incrementAndGet();
            return enabled.get();
        }, Upper::new);

        for (int i = 0; i < 1000; i++) {
            assertFalse(hook.isRegistered("player"));
            assertEquals("%player_name%", hook.setPlaceholders(null, "%player_name%"));
        }
        assertEquals(1, asked.get());

        enabled.set(true);
        Thread.sleep(60);
        assertTrue(hook.isRegistered("player"));
        assertEquals("%PLAYER_NAME%", hook.setPlaceholders(null, "%player_name%"));
        assertEquals(2, asked.get());

        for (int i = 0; i < 1000; i++) {
            hook.isRegistered("player");
        }
        assertEquals(2, asked.get());
    }
}
