package fr.robie.minimessageflow;

import fr.robie.minimessageflow.core.BoundedCache;
import fr.robie.minimessageflow.core.Engine;
import net.kyori.adventure.pointer.Pointered;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Formats MiniMessage text with placeholders. Immutable and thread-safe: build one and share it.
 * <pre>{@code
 * MiniMessageFlow text = MiniMessageFlow.builder()
 *         .resolver(Resolver.of(globals))       // %server%...
 *         .build();
 *
 * Template shop = text.compile("<green>%item%</green> for <gold>%price.comma%</gold>");
 * Component line = shop.render(Placeholder.of("item", "Sword", "price", "1250"), player);
 * }</pre>
 * Compile the messages you send often once and keep the {@link Template}; {@link #format} is for other texts and
 * caches what it compiles.
 */
public final class MiniMessageFlow {

    private final Engine engine;
    private final BoundedCache<String, Template> templates;

    private MiniMessageFlow(@NotNull Builder builder) {
        this.engine = new Engine(builder.legacyCodes, builder.resolvers, builder.tags, builder.valueCacheSize);
        this.templates = new BoundedCache<>(builder.templateCacheSize);
    }

    /**
     * An instance with the defaults: legacy codes read, no resolver, the standard tags.
     */
    public static @NotNull MiniMessageFlow create() {
        return builder().build();
    }

    public static @NotNull Builder builder() {
        return new Builder();
    }

    /**
     * Compiles a message. The template is not cached here: keep it.
     */
    public @NotNull Template compile(@NotNull String message) {
        Objects.requireNonNull(message, "message cannot be null");
        return this.engine.compile(message);
    }

    /**
     * Formats a message, compiling it the first time (cache bounded by {@link Builder#templateCacheSize}).
     */
    public @NotNull Component format(@NotNull String message, @NotNull Placeholder placeholders, @Nullable Pointered viewer) {
        Objects.requireNonNull(message, "message cannot be null");
        Objects.requireNonNull(placeholders, "placeholders cannot be null");
        return this.templates.get(message, this.engine::compile).render(placeholders, viewer);
    }

    public @NotNull Component format(@NotNull String message, @NotNull Placeholder placeholders) {
        return this.format(message, placeholders, null);
    }

    public @NotNull Component format(@NotNull String message) {
        return this.format(message, Placeholder.empty(), null);
    }

    /**
     * Drops the compiled texts of {@link #format} and the cached values. Templates from {@link #compile} keep
     * working.
     */
    public void clearCache() {
        this.templates.clear();
        this.engine.clearCache();
    }

    public static final class Builder {
        private boolean legacyCodes = true;
        private final List<Resolver> resolvers = new ArrayList<>();
        private @Nullable TagResolver tags;
        private int templateCacheSize = 1024;
        private int valueCacheSize = 512;

        private Builder() {
        }

        /**
         * Whether {@code &c}, {@code §c}, {@code &#rrggbb}, {@code #rrggbb} and {@code §x§r§r§g§g§b§b} are read as
         * colours. Default: true.
         */
        public @NotNull Builder legacyCodes(boolean legacyCodes) {
            this.legacyCodes = legacyCodes;
            return this;
        }

        /**
         * Adds a resolver, asked after the call's values and after the resolvers added before it.
         */
        public @NotNull Builder resolver(@NotNull Resolver resolver) {
            this.resolvers.add(Objects.requireNonNull(resolver, "resolver cannot be null"));
            return this;
        }

        /**
         * Extra MiniMessage tags, tried before the standard ones. A tag claimed here is never a {@code <key>}
         * placeholder.
         */
        public @NotNull Builder tags(@NotNull TagResolver tags) {
            this.tags = Objects.requireNonNull(tags, "tags cannot be null");
            return this;
        }

        /**
         * How many texts {@link #format} keeps compiled. Default: 1024.
         */
        public @NotNull Builder templateCacheSize(int size) {
            this.templateCacheSize = positive(size);
            return this;
        }

        /**
         * How many entries each value cache keeps (resolver texts, messages parsed with their values, tag arguments
         * with their values). Default: 512.
         */
        public @NotNull Builder valueCacheSize(int size) {
            this.valueCacheSize = positive(size);
            return this;
        }

        private static int positive(int size) {
            if (size < 1) {
                throw new IllegalArgumentException("size must be at least 1: " + size);
            }
            return size;
        }

        public @NotNull MiniMessageFlow build() {
            return new MiniMessageFlow(this);
        }
    }
}
