package fr.robie.minimessageflow;

import fr.robie.minimessageflow.cache.Cache;
import fr.robie.minimessageflow.cache.CacheFactory;
import fr.robie.minimessageflow.core.BoundedCache;
import fr.robie.minimessageflow.core.Engine;
import fr.robie.minimessageflow.core.EngineSource;
import fr.robie.minimessageflow.core.LiveTemplate;
import fr.robie.minimessageflow.core.LiveText;
import fr.robie.minimessageflow.core.ValueTagResolver;
import net.kyori.adventure.pointer.Pointered;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.minimessage.tag.standard.StandardTags;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

/**
 * Formats MiniMessage text with placeholders. Thread-safe: build one and share it.
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
 * <p>
 * <strong>Changing it after build</strong>: tags, resolvers, value tags and constants can be added and removed at
 * any time ({@link #tag}, {@link #resolver}, {@link #valueTag}, {@link #constant}). Every template it compiled,
 * before or after, uses them from its next render: it compiles its message again once, then renders as fast as
 * before. Several changes in a row cost one rebuild: the instance is made again on its next use, not at each change.
 */
public final class MiniMessageFlow {

    /**
     * What the builder set once: never changes.
     */
    private record Settings(boolean legacyCodes, int valueCacheSize, int templateCacheSize, boolean nativeCompiler,
                            boolean verifyNative, @NotNull CacheFactory caches,
                            @NotNull BiConsumer<String, Throwable> errors, @NotNull ModifierSet modifiers,
                            boolean bareHexColors, @NotNull ValueKeys keys, boolean legacyPrefixModifiers,
                            @NotNull FailedModifier failedModifier) {
    }

    /**
     * A tag resolver added to an instance, with its name when added by {@link #tag(String, Tag)}.
     */
    private record TagEntry(@Nullable String name, @NotNull TagResolver resolver) {
    }

    private final Settings settings;
    private final List<TagEntry> tags = new ArrayList<>();
    /**
     * The resolvers, and the value tags at their place among them ({@link PendingValueTag}).
     */
    private final List<Resolver> resolvers = new ArrayList<>();
    private final Map<String, Object> constants = new LinkedHashMap<>();
    /**
     * The engine for the definitions as they are; null after a change, made again on use.
     */
    private volatile @Nullable Engine engine;
    private final EngineSource source = this::engine;
    private final Cache<String, Template> templates;
    private final ValueKeys keys;
    /**
     * How the number modifiers read numbers, and so how {@link Values} writes a {@code double}.
     */
    private final NumberStyle numberStyle;

    private MiniMessageFlow(@NotNull Builder builder) {
        this.keys = builder.builtKeys();
        ModifierSet modifiers = builder.modifiers.build();
        this.numberStyle = modifiers.numberStyle();
        this.settings = new Settings(builder.legacyCodes, builder.valueCacheSize, builder.templateCacheSize,
                builder.nativeCompiler, builder.verifyNative, builder.caches, Objects.requireNonNull(builder.errors),
                modifiers, builder.bareHexColors, this.keys, builder.legacyPrefixModifiers, builder.failedModifier);
        for (TagResolver tags : builder.tags) {
            this.tags.add(new TagEntry(null, tags));
        }
        this.resolvers.addAll(builder.resolvers);
        this.constants.putAll(builder.constants);
        this.templates = builder.caches.create(CacheFactory.Kind.TEMPLATES, builder.templateCacheSize);
        TagResolver visible = this.visibleTags();
        for (Resolver resolver : this.resolvers) {
            if (resolver instanceof PendingValueTag(String name, ValueTag ignored) && visible != null && visible.has(name)) {
                throw new IllegalArgumentException("<" + name + "> is claimed by a tag given to tags(...): it would never reach the value tag");
            }
        }
        this.engine = this.makeEngine();
    }

    /**
     * The engine for the definitions as they are now, made again after a change.
     */
    private @NotNull Engine engine() {
        Engine engine = this.engine;
        if (engine != null) {
            return engine;
        }
        synchronized (this) {
            engine = this.engine;
            if (engine == null) {
                engine = this.makeEngine();
                this.engine = engine;
            }
            return engine;
        }
    }

    /**
     * Makes an engine from the definitions. Called holding the lock, or from the constructor.
     */
    private @NotNull Engine makeEngine() {
        TagResolver tags = this.visibleTags();
        List<Resolver> resolvers = new ArrayList<>();
        this.ownResolvers(resolvers, tags);
        Map<String, Object> constants = new LinkedHashMap<>();
        this.collectConstants(constants);
        Settings s = this.settings;
        return new Engine(s.legacyCodes(), resolvers, tags, s.valueCacheSize(), s.nativeCompiler(),
                s.verifyNative(), s.caches(), s.errors(), s.modifiers(), s.bareHexColors(), s.keys(), constants,
                s.legacyPrefixModifiers(), s.failedModifier() == FailedModifier.EMPTY);
    }

    /**
     * The tags of this instance (a later one wins a name); null when none.
     */
    private @Nullable TagResolver visibleTags() {
        List<TagResolver> all = new ArrayList<>();
        this.collectTags(all);
        return all.isEmpty() ? null : all.size() == 1 ? all.getFirst() : TagResolver.resolver(all);
    }

    private void collectTags(@NotNull List<TagResolver> out) {
        for (TagEntry entry : this.tags) {
            out.add(entry.resolver());
        }
    }

    private void collectConstants(@NotNull Map<String, Object> out) {
        out.putAll(this.constants);
    }

    /**
     * Adds this instance's own resolvers, the value tags added one after another made one resolver (a key finds
     * its tag with one lookup). A value tag a tag claims is left out: the tag wins its name.
     */
    private void ownResolvers(@NotNull List<Resolver> out, @Nullable TagResolver tags) {
        Map<String, List<ValueTag>> group = new LinkedHashMap<>();
        for (Resolver resolver : this.resolvers) {
            if (resolver instanceof PendingValueTag(String name, ValueTag tag)) {
                if (tags == null || !tags.has(name)) {
                    group.computeIfAbsent(name, n -> new ArrayList<>(1)).add(tag);
                }
            } else {
                if (!group.isEmpty()) {
                    out.add(new ValueTagResolver(group, this.settings.modifiers()));
                    group = new LinkedHashMap<>();
                }
                out.add(resolver);
            }
        }
        if (!group.isEmpty()) {
            out.add(new ValueTagResolver(group, this.settings.modifiers()));
        }
    }

    /**
     * Makes the engine again on its next use. Called holding the lock.
     */
    private void changed() {
        this.engine = null;
        this.templates.clear();
    }

    /**
     * Whether a value tag of this name was added.
     */
    private boolean hasValueTag(@NotNull String name) {
        for (Resolver resolver : this.resolvers) {
            if (resolver instanceof PendingValueTag(String tagName, ValueTag ignored) && tagName.equals(name)) {
                return true;
            }
        }
        return false;
    }

    // --- changes after build ------------------------------------------------------------------------------------

    /**
     * Adds a MiniMessage tag, or replaces the one this instance added with this name. Every template of this
     * instance reads it from its next render; a {@code <name>} that was a placeholder becomes
     * the tag. As with {@link Builder#tag}, a tag of the message: it may be resolved once, when compiled.
     *
     * @throws IllegalArgumentException for a name that is not lower-case letters, digits, '_' and '-', or that a
     *                                  value tag of this instance uses
     */
    public @NotNull MiniMessageFlow tag(@NotNull String name, @NotNull Tag tag) {
        String checked = Builder.tagName(name);
        Objects.requireNonNull(tag, "tag cannot be null");
        synchronized (this) {
            if (this.hasValueTag(checked)) {
                throw new IllegalArgumentException("<" + checked + "> is a value tag: the tag would hide it");
            }
            this.tags.removeIf(entry -> checked.equals(entry.name()));
            this.tags.add(new TagEntry(checked, TagResolver.resolver(checked, tag)));
            this.changed();
        }
        return this;
    }

    /**
     * Adds MiniMessage tags, as {@link Builder#tags}; {@link #removeTags} removes them. A value tag whose name they
     * claim is hidden while they are here.
     */
    public @NotNull MiniMessageFlow tags(@NotNull TagResolver tags) {
        Objects.requireNonNull(tags, "tags cannot be null");
        synchronized (this) {
            this.tags.add(new TagEntry(null, tags));
            this.changed();
        }
        return this;
    }

    /**
     * Removes the tag this instance added with {@link #tag(String, Tag)}.
     *
     * @return whether there was one
     */
    public boolean removeTag(@NotNull String name) {
        Objects.requireNonNull(name, "name cannot be null");
        synchronized (this) {
            boolean removed = this.tags.removeIf(entry -> name.equals(entry.name()));
            if (removed) {
                this.changed();
            }
            return removed;
        }
    }

    /**
     * Removes tags this instance was given, by the builder or {@link #tags(TagResolver)}, compared by identity.
     *
     * @return whether they were here
     */
    public boolean removeTags(@NotNull TagResolver tags) {
        Objects.requireNonNull(tags, "tags cannot be null");
        synchronized (this) {
            boolean removed = this.tags.removeIf(entry -> entry.resolver() == tags);
            if (removed) {
                this.changed();
            }
            return removed;
        }
    }

    /**
     * Adds a resolver, asked after the others.
     */
    public @NotNull MiniMessageFlow resolver(@NotNull Resolver resolver) {
        Objects.requireNonNull(resolver, "resolver cannot be null");
        synchronized (this) {
            this.resolvers.add(resolver);
            this.changed();
        }
        return this;
    }

    /**
     * Removes a resolver of this instance, compared by identity.
     *
     * @return whether it was here
     */
    public boolean removeResolver(@NotNull Resolver resolver) {
        Objects.requireNonNull(resolver, "resolver cannot be null");
        synchronized (this) {
            boolean removed = this.resolvers.removeIf(r -> r == resolver);
            if (removed) {
                this.changed();
            }
            return removed;
        }
    }

    /**
     * Adds a value tag, as {@link Builder#valueTag}: {@code <name:args>} and {@code %name:args%} ask it at each
     * render. Added after one of the same name, it is asked first.
     *
     * @throws IllegalArgumentException for a standard tag's name, or one a tag of this instance claims
     */
    public @NotNull MiniMessageFlow valueTag(@NotNull String name, @NotNull ValueTag tag) {
        String checked = Builder.valueTagName(name);
        Objects.requireNonNull(tag, "tag cannot be null");
        synchronized (this) {
            TagResolver visible = this.visibleTags();
            if (visible != null && visible.has(checked)) {
                throw new IllegalArgumentException("<" + checked + "> is claimed by a tag: it would never reach the value tag");
            }
            // First among this instance's resolvers: the latest value tag of a name wins, as a tag does.
            this.resolvers.addFirst(new PendingValueTag(checked, tag));
            this.changed();
        }
        return this;
    }

    /**
     * Removes the value tags of a name.
     *
     * @return whether there was one
     */
    public boolean removeValueTag(@NotNull String name) {
        Objects.requireNonNull(name, "name cannot be null");
        synchronized (this) {
            boolean removed = this.resolvers.removeIf(r -> r instanceof PendingValueTag(String tagName, ValueTag ignored)
                    && tagName.equals(name));
            if (removed) {
                this.changed();
            }
            return removed;
        }
    }

    /**
     * Sets a constant, as {@link Builder#constant(String, String)}: formatted text that never changes.
     */
    public @NotNull MiniMessageFlow constant(@NotNull String key, @NotNull String text) {
        return this.putConstant(key, Objects.requireNonNull(text, "text cannot be null"));
    }

    public @NotNull MiniMessageFlow constant(@NotNull String key, @NotNull net.kyori.adventure.text.ComponentLike component) {
        return this.putConstant(key, component.asComponent());
    }

    private @NotNull MiniMessageFlow putConstant(@NotNull String key, @NotNull Object value) {
        Objects.requireNonNull(key, "key cannot be null");
        synchronized (this) {
            this.constants.put(key, value);
            this.changed();
        }
        return this;
    }

    /**
     * Removes a constant.
     *
     * @return whether there was one
     */
    public boolean removeConstant(@NotNull String key) {
        Objects.requireNonNull(key, "key cannot be null");
        synchronized (this) {
            boolean removed = this.constants.remove(key) != null;
            if (removed) {
                this.changed();
            }
            return removed;
        }
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
        return new LiveTemplate(this.source, message);
    }

    /**
     * Compiles lines (an item's lore, a help page from a configuration's list), rendered as a list of components.
     * See {@link TemplateList}. Not cached here: keep it.
     */
    public @NotNull TemplateList compileList(@NotNull List<String> lines) {
        Objects.requireNonNull(lines, "lines cannot be null");
        List<Template> templates = new ArrayList<>(lines.size());
        for (String line : lines) {
            templates.add(this.compile(Objects.requireNonNull(line, "a line cannot be null")));
        }
        return TemplateList.of(templates);
    }

    /**
     * Compiles a text rendered as a {@code String} (a command, an amount, a URL): only its {@code %key%}
     * placeholders are read, every value placed as written. See {@link TextTemplate}. Not cached here: keep it.
     */
    public @NotNull TextTemplate compileText(@NotNull String text) {
        Objects.requireNonNull(text, "text cannot be null");
        return new LiveText(this.source, text);
    }

    /**
     * New {@link Values} for the keys declared on the builder ({@link Builder#key}), all unset: make one per thread
     * that renders, set it for each send, and give it to {@link Template#render(Values, Pointered)}.
     */
    public @NotNull Values newValues() {
        return this.keys.newValues(this.numberStyle);
    }

    /**
     * The keys of this instance: give them to {@link Builder#keys} to build another instance its {@link Values} work
     * with.
     */
    public @NotNull ValueKeys keys() {
        return this.keys;
    }

    /**
     * Declares a key after the instance is built (a name read from a configuration), or gives the one already
     * declared. Thread-safe. A message compiled after the key is declared reads it by its place; one compiled before
     * reads it by its name, slower: declare the keys of a file before compiling its messages.
     *
     * @throws IllegalArgumentException when no placeholder can have this name
     */
    public @NotNull ValueKey key(@NotNull String name) {
        return this.keys.add(name);
    }

    /**
     * Compiles the messages of a configuration at once, for a reload: the templates by the same keys, in the same
     * order. {@link Template#kind()} tells which ones are slow to render, {@link Template#placeholders()} what each
     * one asks for.
     */
    public @NotNull Map<String, Template> compileAll(@NotNull Map<String, String> messages) {
        Objects.requireNonNull(messages, "messages cannot be null");
        Map<String, Template> templates = new LinkedHashMap<>(messages.size() * 2);
        messages.forEach((key, message) -> templates.put(key, this.compile(message)));
        return Collections.unmodifiableMap(templates);
    }

    /**
     * Formats a message, compiling it the first time (cache bounded by {@link Builder#templateCacheSize}).
     */
    public @NotNull Component format(@NotNull String message, @NotNull Placeholder placeholders, @Nullable Pointered viewer) {
        Objects.requireNonNull(message, "message cannot be null");
        Objects.requireNonNull(placeholders, "placeholders cannot be null");
        return this.templates.get(message, this::compile).render(placeholders, viewer);
    }

    public @NotNull Component format(@NotNull String message, @NotNull Placeholder placeholders) {
        return this.format(message, placeholders, null);
    }

    /**
     * As {@link #format(String, Placeholder, Pointered)}, with the values in a {@link Values} of this instance.
     */
    public @NotNull Component format(@NotNull String message, @NotNull Values values, @Nullable Pointered viewer) {
        Objects.requireNonNull(message, "message cannot be null");
        Objects.requireNonNull(values, "values cannot be null");
        return this.templates.get(message, this::compile).render(values, viewer);
    }

    public @NotNull Component format(@NotNull String message, @NotNull Values values) {
        return this.format(message, values, null);
    }

    public @NotNull Component format(@NotNull String message) {
        return this.format(message, Placeholder.empty(), null);
    }

    /**
     * As {@link #format}, as plain text without colours or events: for a console or a log.
     */
    public @NotNull String formatPlain(@NotNull String message, @NotNull Placeholder placeholders, @Nullable Pointered viewer) {
        return PlainTextComponentSerializer.plainText().serialize(this.format(message, placeholders, viewer));
    }

    public @NotNull String formatPlain(@NotNull String message, @NotNull Placeholder placeholders) {
        return this.formatPlain(message, placeholders, null);
    }

    /**
     * Drops the compiled texts of {@link #format} and the cached values. Templates from {@link #compile} keep
     * working.
     */
    public void clearCache() {
        this.templates.clear();
        Engine engine = this.engine;
        if (engine != null) {
            engine.clearCache();
        }
    }

    /**
     * The default caches: {@link BoundedCache}.
     */
    private static final CacheFactory DEFAULT_CACHES = new CacheFactory() {
        @Override
        public <K, V> @NotNull Cache<K, V> create(@NotNull Kind kind, int maximumSize) {
            return new BoundedCache<>(maximumSize);
        }
    };

    /**
     * The default error handler: one warning per key (the first 256 keys), so a broken hook shows in the log without
     * a line per message sent.
     */
    private static @NotNull BiConsumer<String, Throwable> logOncePerKey() {
        Set<String> reported = ConcurrentHashMap.newKeySet();
        return (key, error) -> {
            if (reported.size() < 256 && reported.add(key)) {
                System.getLogger("MiniMessageFlow").log(System.Logger.Level.WARNING,
                        "The value of the placeholder '" + key + "' threw; it is shown as if it had no value. "
                                + "Reported once per placeholder.", error);
            }
        };
    }

    /**
     * A value tag added to the builder, kept at its place among the resolvers until the instance is built and knows
     * its modifiers. Never asked.
     */
    private record PendingValueTag(@NotNull String name, @NotNull ValueTag tag) implements Resolver {
        @Override
        public @Nullable String resolve(@NotNull String key, @Nullable Pointered viewer) {
            throw new IllegalStateException("not built");
        }
    }

    public static final class Builder {
        private boolean legacyCodes = true;
        /**
         * The resolvers, and the value tags at their place among them ({@link PendingValueTag}, made into resolvers
         * when the instance is built, with its modifiers).
         */
        private final List<Resolver> resolvers = new ArrayList<>();
        private final List<TagResolver> tags = new ArrayList<>();
        private int templateCacheSize = 1024;
        private int valueCacheSize = 512;
        private boolean nativeCompiler = true;
        private boolean verifyNative = true;
        private CacheFactory caches = DEFAULT_CACHES;
        private @Nullable BiConsumer<String, Throwable> errors;
        private final ModifierSet.Builder modifiers = ModifierSet.builder();
        private final ValueKeys.Builder keys = ValueKeys.builder();
        private @Nullable ValueKeys sharedKeys;
        private boolean declaredOwnKeys;
        private final Map<String, Object> constants = new LinkedHashMap<>();
        private boolean bareHexColors = true;
        private boolean legacyPrefixModifiers;
        private FailedModifier failedModifier = FailedModifier.WRITTEN;

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
         * Whether a {@code #rrggbb} written without {@code &} is a colour, when legacy codes are read. Default: true.
         * With false, {@code Issue #123456} keeps its number; {@code &#rrggbb} and {@code <#rrggbb>} are still
         * colours.
         */
        /**
         * Whether {@code %upper_x%}, {@code %lower_x%}, {@code %capitalize_x%} (first letter only),
         * {@code %add_one_x%} and {@code %remove_one_x%} read {@code x} with that modifier, as older placeholder
         * parsers did. A value set for {@code upper_x} itself is read first. Default: false. Read when a message is
         * compiled: a render pays nothing for it.
         */
        public @NotNull Builder legacyPrefixModifiers(boolean legacyPrefixModifiers) {
            this.legacyPrefixModifiers = legacyPrefixModifiers;
            return this;
        }

        /**
         * What a placeholder with a value gives when its modifiers don't apply: as written (the default), or the
         * empty text. See {@link FailedModifier}.
         */
        public @NotNull Builder failedModifier(@NotNull FailedModifier failedModifier) {
            this.failedModifier = Objects.requireNonNull(failedModifier, "failedModifier cannot be null");
            return this;
        }

        public @NotNull Builder bareHexColors(boolean bareHexColors) {
            this.bareHexColors = bareHexColors;
            return this;
        }

        /**
         * How the built-in number modifiers ({@code comma}, {@code add:n}, {@code 2f}, {@code plural}...) read a value's
         * separators and write the numbers they give: {@code 1,234.50} or {@code 1.234,50}. {@link NumberStyle#AUTO}
         * by default. A plugin can let its users choose it in its configuration.
         */
        public @NotNull Builder numberStyle(@NotNull NumberStyle numberStyle) {
            this.modifiers.numberStyle(numberStyle);
            return this;
        }

        /**
         * Adds a modifier of this instance only ({@code %key.name%}, {@code <key:name>}), used before a shared one of
         * {@link PlaceholderModifiers} with the same name.
         *
         * @param name the name, case-insensitive, without {@code .} or {@code :}
         */
        public @NotNull Builder modifier(@NotNull String name, @NotNull PlaceholderModifiers.Modifier modifier) {
            this.modifiers.add(name, modifier);
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
         * Adds MiniMessage tags, tried before the standard ones; each call adds to the ones before it, and for a tag
         * two calls claim, the last one added wins (as a value registered again replaces the first). A tag claimed
         * here is never a {@code <key>} placeholder.
         * <p>
         * These are tags of the message itself (a style, a prefix, a custom gradient): one may be resolved once, when
         * the message is compiled, and is not given the viewer. It must give the same result for the same arguments.
         * For a value that changes with the viewer or with time, use {@link #valueTag}.
         */
        public @NotNull Builder tags(@NotNull TagResolver tags) {
            this.tags.add(Objects.requireNonNull(tags, "tags cannot be null"));
            return this;
        }

        /**
         * Adds one MiniMessage tag, as {@link #tags} does: {@code tag("prefix", Tag.selfClosingInserting(prefix))}.
         *
         * @param name the tag's name, lower case: letters, digits, {@code _} and {@code -}
         */
        public @NotNull Builder tag(@NotNull String name, @NotNull Tag tag) {
            Objects.requireNonNull(tag, "tag cannot be null");
            return this.tags(TagResolver.resolver(tagName(name), tag));
        }

        /**
         * Adds a tag whose value is computed at each render, for the viewer: {@code <papi:player_name>},
         * {@code <online>}. It is a placeholder with arguments ({@code <name:a:b>} and {@code %name:a:b%} are the same)
         * and is asked with the resolvers, in the order they are added: see {@link ValueTag}.
         *
         * @param name the tag's name, lower case: letters, digits, {@code _} and {@code -}; not a standard tag's
         *             ({@code red}, {@code click}...) nor one of {@link #tags}', which would claim it first
         * @throws IllegalArgumentException for another name, checked when the instance is built for the tags'
         */
        public @NotNull Builder valueTag(@NotNull String name, @NotNull ValueTag tag) {
            String checked = valueTagName(name);
            this.resolvers.add(new PendingValueTag(checked, Objects.requireNonNull(tag, "tag cannot be null")));
            return this;
        }

        static @NotNull String valueTagName(@NotNull String name) {
            String checked = tagName(name);
            if (StandardTags.defaults().has(checked)) {
                throw new IllegalArgumentException("'" + checked + "' is a standard tag: <" + checked + "> would never reach the value tag");
            }
            return checked;
        }

        static @NotNull String tagName(@NotNull String name) {
            Objects.requireNonNull(name, "name cannot be null");
            if (name.isEmpty() || !name.chars().allMatch(c -> c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '_' || c == '-')) {
                throw new IllegalArgumentException("a tag's name is lower-case letters, digits, '_' and '-': '" + name + "'");
            }
            return name;
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

        /**
         * Whether messages made only of the tags the native compiler knows (colours, decorations, {@code reset},
         * {@code newline}, {@code click}, {@code hover}, {@code insert}, {@code font}, {@code key}, {@code lang},
         * {@code gradient}, {@code rainbow}, {@code transition}, placeholders, and on Adventure 5 {@code shadow},
         * {@code sprite}, {@code head}) are compiled without MiniMessage, so a render builds the component directly.
         * Others always go through MiniMessage. Default: true.
         */
        public @NotNull Builder nativeCompiler(boolean nativeCompiler) {
            this.nativeCompiler = nativeCompiler;
            return this;
        }

        /**
         * Whether each natively compiled message is checked against MiniMessage when it is compiled, and left to
         * MiniMessage when they differ. Default: true. Turning it off makes compiling cheaper and is how the tests
         * check the native compiler itself.
         */
        @ApiStatus.Experimental
        public @NotNull Builder verifyNative(boolean verifyNative) {
            this.verifyNative = verifyNative;
            return this;
        }

        /**
         * How the caches are made. Default: bounded maps without expiry, dropping about a quarter of their entries
         * when full (no dependency). A platform can pass its own, for example Guava or Caffeine with an expiry.
         */
        public @NotNull Builder cacheFactory(@NotNull CacheFactory caches) {
            this.caches = Objects.requireNonNull(caches, "caches cannot be null");
            return this;
        }

        /**
         * Told when a {@link Resolver} or a value of a call ({@code Placeholder.of("x", () -> ...)}) throws: the key
         * as written, and the exception. The render goes on as if the key had no value from it. Called on the thread
         * that renders, possibly often: it must be fast and thread-safe. Default: a warning in the log
         * ({@code System.getLogger("MiniMessageFlow")}), once per key.
         */
        public @NotNull Builder errorHandler(@NotNull BiConsumer<String, Throwable> handler) {
            this.errors = Objects.requireNonNull(handler, "handler cannot be null");
            return this;
        }

        /**
         * A value that never changes for this instance ({@code %prefix%}, {@code %server%}): formatted once, and
         * known when a message is compiled. A message whose only placeholders are constants is rendered once and
         * is {@link Template.Kind#STATIC}. The text is formatted (MiniMessage and legacy codes are read) on its
         * own, as a resolver's text: its colours end with it.
         * <p>
         * A constant is not asked again: a value given to a render for the same key is ignored. For a value that
         * can change, use a {@link Resolver}.
         *
         * @param key the placeholder's name, without {@code %} or modifiers
         */
        public @NotNull Builder constant(@NotNull String key, @NotNull String text) {
            this.constants.put(Objects.requireNonNull(key, "key cannot be null"), Objects.requireNonNull(text, "text cannot be null"));
            return this;
        }

        /**
         * As {@link #constant(String, String)}, with a component inserted as it is.
         */
        public @NotNull Builder constant(@NotNull String key, @NotNull net.kyori.adventure.text.ComponentLike component) {
            this.constants.put(Objects.requireNonNull(key, "key cannot be null"), component.asComponent());
            return this;
        }

        /**
         * Declares a placeholder whose value will be given through {@link Values}: keep the key in a constant.
         * Its value is then read by its place, without a lookup by name; {@code %name.comma%} reads it too.
         * A {@link Placeholder} still works for the same name.
         *
         * @param name the placeholder's name, without {@code %} or modifiers
         */
        public @NotNull ValueKey key(@NotNull String name) {
            if (this.sharedKeys != null) {
                return this.sharedKeys.add(name);
            }
            this.declaredOwnKeys = true;
            return this.keys.key(name);
        }

        /**
         * Builds the instance on keys that already exist, those of another instance ({@code other.keys()}) or made
         * with {@code ValueKeys.builder().build()}, instead of new ones. Instances on the same keys accept each
         * other's {@link ValueKey}s and {@link Values}: a plugin that builds a new instance at each reload (new tags,
         * a new number style) keeps the keys and the {@code Values} it made before. Keys declared on this builder
         * after this call are added to them.
         *
         * @param keys the keys, which can still be added to
         * @throws IllegalStateException when {@link #key} was called before: those keys could not be read by the
         *                               instance; call this first
         */
        public @NotNull Builder keys(@NotNull ValueKeys keys) {
            Objects.requireNonNull(keys, "keys cannot be null");
            if (this.declaredOwnKeys) {
                throw new IllegalStateException("keys(ValueKeys) must be called before key(name)");
            }
            this.sharedKeys = keys;
            return this;
        }

        @NotNull ValueKeys builtKeys() {
            return this.sharedKeys != null ? this.sharedKeys : this.keys.build();
        }

        private static int positive(int size) {
            if (size < 1) {
                throw new IllegalArgumentException("size must be at least 1: " + size);
            }
            return size;
        }

        public @NotNull MiniMessageFlow build() {
            if (this.errors == null) {
                this.errors = logOncePerKey();
            }
            return new MiniMessageFlow(this);
        }
    }
}
