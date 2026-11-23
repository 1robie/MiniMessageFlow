package fr.robie.minimessageflow.core;

import fr.robie.minimessageflow.Placeholder;
import fr.robie.minimessageflow.ModifierSet;
import fr.robie.minimessageflow.PlaceholderModifiers;
import fr.robie.minimessageflow.PlaceholderValue;
import fr.robie.minimessageflow.Resolver;
import fr.robie.minimessageflow.Template;
import fr.robie.minimessageflow.ValueKeys;
import fr.robie.minimessageflow.Values;
import fr.robie.minimessageflow.cache.Cache;
import fr.robie.minimessageflow.cache.CacheFactory;
import fr.robie.minimessageflow.internal.PlaceholderScanner;
import net.kyori.adventure.pointer.Pointered;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.Style;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.minimessage.Context;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.ArgumentQueue;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.minimessage.tag.standard.StandardTags;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.UnaryOperator;

/**
 * The stage 1 engine: MiniMessage parses each message once, with a marker where each placeholder goes, and each
 * render swaps the markers for the values (MessageFlow 1.1's technique, compiled once per template instead of looked
 * up per send).
 * <ul>
 *     <li>{@code %key%} outside a tag becomes a slot tag {@code <mmf_slot_N>}; {@code <key>} (a tag no standard or
 *     user tag claims) is a slot too. Both parse to a marker text, swapped at render.</li>
 *     <li>{@code %key%} inside a tag ({@code <click:run_command:'/tp %key%'>}) is written into the text at render,
 *     as plain text without the characters that could end the tag, and that text is compiled once per value
 *     (bounded cache).</li>
 *     <li>A message whose markers don't survive parsing ({@code <gradient>} and {@code <rainbow>} restyle what they
 *     wrap, a hover keeps its own copy) is parsed with its values on each render, cached by values.</li>
 *     <li>An unknown {@code <key>} must read as MiniMessage reads an unknown tag: the message is compiled again
 *     without claiming it, once per set of unknown keys.</li>
 *     <li>Text from a {@link Resolver} is parsed on its own (cached by text) and inserted like a component value:
 *     its colours and unclosed tags end with it.</li>
 * </ul>
 */
@ApiStatus.Internal
public final class Engine {

    private static final char MARKER_START = '﷐';
    private static final char MARKER_END = '﷑';
    static final char INLINE_START = '﷒';
    static final char INLINE_END = '﷓';
    private static final String SLOT_TAG = "mmf_slot_";
    /**
     * Around the probe values of {@link #compileForm}: a private use character, never in a marker.
     */
    static final char PROBE = '';
    private static final int MAX_VARIANTS = 64;
    private static final int[] NO_TAGS = new int[0];
    /**
     * Compared by identity: a resolver's component that threw.
     */
    private static final Component FAILED = Component.text("failed");
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();
    /**
     * The prefixes read as a modifier before the key ({@code %upper_name%}), with {@code legacyPrefixes}.
     */
    private static final String[] LEGACY_PREFIXES = {"upper_", "lower_", "capitalize_", "add_one_", "remove_one_"};
    private static final String[] LEGACY_MODIFIERS = {"upper", "lower", "capitalize", "add_one", "remove_one"};
    private static final int CAPITALIZE_PREFIX = 2;
    /**
     * {@code %capitalize_x%}: the first letter in upper case, the rest as it is (where {@code .capitalize} lowers it).
     */
    private static final PlaceholderModifiers.Chain CAPITALIZE_FIRST = PlaceholderModifiers.Chain.of((value, args) ->
            value.isEmpty() ? value : value.substring(0, 1).toUpperCase(java.util.Locale.ROOT) + value.substring(1));
    private static final String RAW_SUFFIX = "." + PlaceholderModifiers.RAW;

    private final boolean legacyCodes;
    private final boolean nativeCompiler;
    private final boolean verifyNative;
    private final List<Resolver> resolvers;
    private final @Nullable TagResolver userTags;
    private final TagResolver tags;
    /**
     * Whether {@code %upper_x%}, {@code %lower_x%}, {@code %capitalize_x%}, {@code %add_one_x%} and
     * {@code %remove_one_x%} read {@code x} with that modifier, when {@code upper_x} itself has no value.
     */
    private final boolean legacyPrefixes;
    /**
     * Whether a value whose modifiers don't apply gives the empty text instead of the placeholder as written.
     */
    private final boolean emptyOnFailedModifier;
    /**
     * Whether the {@code %key%} placeholders of a formatted value's text are read through the resolvers at render.
     */
    private final boolean placeholdersInFormattedValues;
    /**
     * Changes a message before it is compiled (font images, emoji codes...), or null.
     */
    private final @Nullable UnaryOperator<String> preprocessor;
    private final MiniMessage miniMessage;
    private final MiniMessage templateMiniMessage;
    private final AdventureBridge bridge;
    private final Cache<String, Component> resolverTexts;
    private final Cache<ValuesKey, Component> withValues;
    private final Cache<InlineKey, Form> inlineForms;
    /**
     * The messages and texts of {@code format} and {@code formatText}, compiled by this engine: a change of
     * definitions makes another engine, so they never outlive the definitions they were compiled with.
     */
    private final Cache<String, Template> formatTemplates;
    private final Cache<String, fr.robie.minimessageflow.TextTemplate> formatTexts;
    private final BiConsumer<String, Throwable> errors;
    private final ModifierSet modifiers;
    private final boolean bareHex;
    private final ValueKeys keys;
    /**
     * The values that never change for this instance, by key, formatted once.
     */
    private final Map<String, Component> constants;
    /**
     * Parses a resolver's or a formatted value's text: made once, so a cache hit allocates nothing.
     */
    private final java.util.function.Function<String, Component> parseText = this::parseNewText;

    /**
     * @param legacyCodes whether {@code &c}, {@code §c}, {@code #rrggbb}... are read
     * @param resolvers   asked in order for the placeholders a call doesn't give
     * @param userTags    extra MiniMessage tags, tried before the standard ones; null for none
     * @param cacheSize   the size of each value cache
     * @param caches      makes the caches
     * @param nativeCompiler whether messages made only of native tags are compiled without MiniMessage
     * @param verifyNative   whether each native template is checked against MiniMessage when compiled (false only
     *                       to test the native compiler itself)
     * @param errors         told the key and the exception when a resolver or a value throws
     * @param modifiers      the modifiers of placeholders
     * @param bareHex        whether a {@code #rrggbb} without {@code &} is a colour (with legacy codes on)
     * @param keys           the keys declared for {@link Values}
     * @param constants      the values that never change, by key: formatted text ({@code String}) or a
     *                       {@code Component}
     */
    public Engine(boolean legacyCodes, @NotNull List<Resolver> resolvers, @Nullable TagResolver userTags, int cacheSize,
                  boolean nativeCompiler, boolean verifyNative, @NotNull CacheFactory caches,
                  @NotNull BiConsumer<String, Throwable> errors, @NotNull ModifierSet modifiers, boolean bareHex,
                  @NotNull ValueKeys keys, @NotNull Map<String, Object> constants, boolean legacyPrefixes,
                  boolean emptyOnFailedModifier, boolean placeholdersInFormattedValues,
                  @Nullable UnaryOperator<String> preprocessor, int templateCacheSize) {
        this.preprocessor = preprocessor;
        this.placeholdersInFormattedValues = placeholdersInFormattedValues;
        this.legacyPrefixes = legacyPrefixes;
        this.emptyOnFailedModifier = emptyOnFailedModifier;
        this.keys = keys;
        this.errors = errors;
        this.modifiers = modifiers;
        this.bareHex = bareHex;
        this.legacyCodes = legacyCodes;
        this.nativeCompiler = nativeCompiler;
        this.verifyNative = verifyNative;
        this.resolvers = List.copyOf(resolvers);
        this.userTags = userTags;
        // In a TagResolver builder the last resolver wins a name: user tags go after the standard ones.
        this.tags = userTags == null ? StandardTags.defaults()
                : TagResolver.builder().resolver(StandardTags.defaults()).resolver(userTags).build();
        this.miniMessage = MiniMessage.builder().tags(this.tags).build();
        this.templateMiniMessage = MiniMessage.builder()
                .tags(this.tags)
                // Compacting could merge a marker with its neighbours: a parse with markers is compacted once the
                // values are in.
                .postProcessor(component -> this.hasMarker(component) ? component : component.compact())
                .build();
        this.bridge = AdventureBridges.current();
        this.resolverTexts = caches.create(CacheFactory.Kind.RESOLVER_TEXTS, cacheSize);
        this.withValues = caches.create(CacheFactory.Kind.PARSED_WITH_VALUES, cacheSize);
        this.inlineForms = caches.create(CacheFactory.Kind.INLINE_TEXTS, cacheSize);
        this.formatTemplates = caches.create(CacheFactory.Kind.TEMPLATES, templateCacheSize);
        this.formatTexts = caches.create(CacheFactory.Kind.TEXT_TEMPLATES, templateCacheSize);
        Map<String, Component> formatted = new HashMap<>();
        constants.forEach((key, value) -> formatted.put(key, value instanceof Component component ? component
                // Text is read on its own, as a resolver's: its colours end with it.
                : this.miniMessage.deserialize(this.legacy(stripNoncharacters((String) value)))));
        this.constants = Map.copyOf(formatted);
    }

    public void clearCache() {
        this.resolverTexts.clear();
        this.withValues.clear();
        this.inlineForms.clear();
        this.formatTemplates.clear();
        this.formatTexts.clear();
    }

    /**
     * A message of {@code format}, compiled the first time.
     */
    public @NotNull Template formatTemplate(@NotNull String message) {
        return this.formatTemplates.get(message, this::compile);
    }

    /**
     * A text of {@code formatText}, compiled the first time.
     */
    public @NotNull fr.robie.minimessageflow.TextTemplate formatText(@NotNull String text) {
        return this.formatTexts.get(text, this::compileText);
    }

    // --- compiling ----------------------------------------------------------------------------------------------

    /**
     * Compiles a message whose renders get a style on their root ({@code Template.style}), or none.
     */
    public @NotNull Template compile(@NotNull String source, @Nullable Style style) {
        Template template = this.compile(source);
        if (style != null && !style.isEmpty()) {
            switch (template) {
                case CompiledTemplate compiled -> compiled.applyStyle(style);
                case NestedTemplate nested -> nested.applyStyle(style);
                default -> throw new IllegalStateException("not compiled here: " + template);
            }
        }
        return template;
    }

    public @NotNull Template compile(@NotNull String source) {
        if (this.preprocessor != null) {
            source = this.preprocess(source);
        }
        if (PlaceholderScanner.hasNested(source)) {
            return new NestedTemplate(this, source);
        }
        Set<Integer> unquoted = new java.util.HashSet<>();
        String text = PlaceholderScanner.quoteArguments(stripNoncharacters(source), Engine::isKey, unquoted);
        Slots slots = new Slots();
        List<String> inlineKeys = new ArrayList<>(0);
        List<Boolean> inlineUnquoted = new ArrayList<>(0);
        int[] tags = text.indexOf('%') < 0 ? NO_TAGS : PlaceholderScanner.tagRanges(text);
        String tagged = PlaceholderScanner.replace(text, (key, start) -> {
            if (!isKey(key)) {
                return null;
            }
            if (PlaceholderScanner.inTag(tags, start)) {
                inlineKeys.add(key);
                inlineUnquoted.add(unquoted.contains(start));
                return INLINE_START + String.valueOf(inlineKeys.size() - 1) + INLINE_END;
            }
            return "<" + SLOT_TAG + slots.percent(key) + ">";
        });
        List<Object> pieces = inlineKeys.isEmpty() ? null : pieces(tagged);
        String compiled = pieces == null ? tagged : join(pieces, new String[inlineKeys.size()]);
        boolean[] colonBreaking = new boolean[inlineUnquoted.size()];
        for (int i = 0; i < colonBreaking.length; i++) {
            colonBreaking[i] = inlineUnquoted.get(i);
        }
        String[] reason = new String[1];
        Template compiledNatively = this.compileNatively(source, tagged, compiled, slots, pieces, inlineKeys, colonBreaking, reason);
        if (compiledNatively != null) {
            return compiledNatively;
        }
        // Finds the <key> slots; for a message without inline values, this is also its form.
        Form base = this.compileForm(compiled, slots, SlotSet.EMPTY, true);
        slots.freeze();
        Form.Native inline = pieces == null ? null : this.compileInline(tagged, pieces, inlineKeys.size(), slots);
        return new CompiledTemplate(this, source, compiled, slots, pieces, List.copyOf(inlineKeys), colonBreaking, base, inline, reason[0]);
    }

    /**
     * Compiles a message with the native compiler first, before any MiniMessage parse: it finds the {@code <key>}
     * placeholders itself (in a copy of the slot table, dropped if it gives up). With the check on, one MiniMessage
     * parse compares the result (two with a gradient or rainbow, whose colours depend on the values' length); with it
     * off, none.
     *
     * @return the template, or null when the message needs the MiniMessage path
     */
    private @Nullable Template compileNatively(@NotNull String source, @NotNull String tagged, @NotNull String compiled,
                                               @NotNull Slots slots, @Nullable List<Object> pieces, @NotNull List<String> inlineKeys,
                                               boolean @NotNull [] colonBreaking, @Nullable String @NotNull [] reason) {
        if (!this.nativeCompiler) {
            reason[0] = "the native compiler is off";
            return null;
        }
        Slots trial = slots.copy();
        List<NativeCompiler.Part> parts = NativeCompiler.compile(this.legacy(tagged), StandardTags.defaults(), this.userTags, this.modifiers,
                trial, SlotSet.EMPTY, true, reason);
        if (parts == null) {
            return null;
        }
        reason[0] = "the native result differs from MiniMessage's";
        trial.freeze();
        if (this.verifyNative) {
            boolean transform = NativeCompiler.hasTransform(parts);
            for (boolean longer : transform ? new boolean[]{false, true} : new boolean[]{true}) {
                Component[] probe = this.probe(trial, SlotSet.EMPTY, longer);
                String[] inline = new String[inlineKeys.size()];
                for (int i = 0; i < inline.length; i++) {
                    inline[i] = PROBE + "i" + i + (longer ? "-longer probe value" : "") + PROBE;
                }
                String text = pieces == null ? compiled : join(pieces, inline);
                Component reparsed = this.miniMessage.deserialize(this.legacy(text), new SlotTags(trial, SlotSet.EMPTY, false, probe));
                if (!same(NativeCompiler.render(parts, probe, inline), reparsed)) {
                    return null;
                }
            }
        }
        List<String> inlineKeysCopy = List.copyOf(inlineKeys);
        if (pieces != null) {
            Form.Native inline = new Form.Native(parts, null);
            return new CompiledTemplate(this, source, compiled, trial, pieces, inlineKeysCopy, colonBreaking, inline, inline, null);
        }
        Form base = trial.size() == 0
                ? new Form.Static(Objects.requireNonNull(NativeCompiler.render(parts, new Component[0])))
                : new Form.Native(parts, new Form.WithValues(compiled, SlotSet.EMPTY, trial));
        return new CompiledTemplate(this, source, compiled, trial, null, inlineKeysCopy, colonBreaking, base, null, null);
    }

    @NotNull Form compileForm(@NotNull String text, @NotNull Slots slots, @NotNull SlotSet unclaimed, boolean discover) {
        SlotTags slotTags = new SlotTags(slots, unclaimed, discover, null);
        Component parsed = this.templateMiniMessage.deserialize(this.legacy(text), slotTags);
        if (!slotTags.claimedAny) {
            return new Form.Static(parsed);
        }
        Form.WithValues withValues = new Form.WithValues(text, unclaimed, slots);
        // MiniMessage resolves a tag several times per parse (how many depends on the version), and parses a hover's
        // text, translatable arguments... apart: counting markers can't tell whether each one can be swapped. So
        // render both ways with unique values and keep the swap only when it gives the same component. Twice, with
        // values of two lengths: a gradient or rainbow (without a virtual component, Adventure 4.17) colours by
        // length, and a probe as long as a marker would not see it.
        Component[] shortProbe = this.probe(slots, unclaimed, false);
        Component[] longProbe = this.probe(slots, unclaimed, true);
        Component shortParsed = this.miniMessage.deserialize(this.legacy(text), new SlotTags(slots, unclaimed, false, shortProbe));
        Component longParsed = this.miniMessage.deserialize(this.legacy(text), new SlotTags(slots, unclaimed, false, longProbe));
        if (this.nativeCompiler) {
            List<NativeCompiler.Part> parts = NativeCompiler.compile(this.legacy(text), StandardTags.defaults(), this.userTags, this.modifiers,
                    slots, unclaimed);
            // Checked against MiniMessage with the same probe values: a message the native compiler would read
            // differently stays with MiniMessage.
            if (parts != null && (!this.verifyNative || (same(NativeCompiler.render(parts, shortProbe), shortParsed)
                    && same(NativeCompiler.render(parts, longProbe), longParsed)))) {
                return new Form.Native(parts, withValues);
            }
        }
        if (this.hasVirtual(parsed)) {
            return withValues;
        }
        boolean swappable = this.insert(parsed, shortProbe).compact().equals(shortParsed)
                && this.insert(parsed, longProbe).compact().equals(longParsed);
        return swappable ? new Form.Markers(parsed, spine(parsed)) : withValues;
    }

    /**
     * Unique plain values for the slots, short (as long as a marker) or long.
     */
    private @Nullable Component @NotNull [] probe(@NotNull Slots slots, @NotNull SlotSet unclaimed, boolean longer) {
        Component[] probe = new Component[slots.size()];
        for (int slot = 0; slot < probe.length; slot++) {
            probe[slot] = unclaimed.contains(slot) ? null
                    : Component.text(PROBE + String.valueOf(slot) + (longer ? "-longer probe value" : "") + PROBE);
        }
        return probe;
    }

    private static boolean same(@Nullable Component rendered, @NotNull Component expected) {
        return rendered != null && Rendering.same(rendered, expected);
    }

    /**
     * A message with values in tag arguments, compiled once natively: only a click's or insertion's argument may
     * hold them, and the event is built at render (stage 3). Null when the message needs the stage 1 path (text
     * compiled once per value).
     */
    @Nullable Form.Native compileInline(@NotNull String tagged, @NotNull List<Object> pieces, int inlineCount, @NotNull Slots slots) {
        if (!this.nativeCompiler) {
            return null;
        }
        List<NativeCompiler.Part> parts = NativeCompiler.compile(this.legacy(tagged), StandardTags.defaults(), this.userTags, this.modifiers,
                slots, SlotSet.EMPTY);
        if (parts == null) {
            return null;
        }
        if (this.verifyNative) {
            for (boolean longer : new boolean[]{false, true}) {
                Component[] probe = this.probe(slots, SlotSet.EMPTY, longer);
                String[] inline = new String[inlineCount];
                for (int i = 0; i < inlineCount; i++) {
                    inline[i] = PROBE + "i" + i + (longer ? "-longer probe value" : "") + PROBE;
                }
                Component reparsed = this.miniMessage.deserialize(this.legacy(join(pieces, inline)), new SlotTags(slots, SlotSet.EMPTY, false, probe));
                if (!same(NativeCompiler.render(parts, probe, inline), reparsed)) {
                    return null;
                }
            }
        }
        return new Form.Native(parts, null);
    }

    /**
     * Renders a native form with inline values, or null when it must go the stage 1 way this time.
     */
    @Nullable Component renderInline(@NotNull Form.Native form, @Nullable Object @NotNull [] values, @NotNull String @NotNull [] inline) {
        return NativeCompiler.render(form.parts(), values, inline);
    }

    @NotNull Form inlineForm(@NotNull String text, @NotNull Slots slots, @NotNull SlotSet unclaimed) {
        return this.inlineForms.get(new InlineKey(text, unclaimed), key -> this.compileForm(text, slots, unclaimed, false));
    }

    /**
     * Tags that become slots: {@code <mmf_slot_N>}, and any tag that no standard or user tag claims.
     */
    private final class SlotTags implements TagResolver {
        private final Slots slots;
        private final SlotSet unclaimed;
        private final boolean discover;
        /**
         * The values to insert, or null to insert markers.
         */
        private final @Nullable Component @Nullable [] values;
        /**
         * Whether a slot was claimed at least once. Not a count: MiniMessage resolves a tag several times.
         */
        boolean claimedAny;

        SlotTags(@NotNull Slots slots, @NotNull SlotSet unclaimed, boolean discover, @Nullable Component @Nullable [] values) {
            this.slots = slots;
            this.unclaimed = unclaimed;
            this.discover = discover;
            this.values = values;
        }

        @Override
        public @Nullable Tag resolve(@NotNull String name, @NotNull ArgumentQueue arguments, @NotNull Context ctx) {
            int slot = slotTagIndex(name);
            if (slot < 0) {
                if (Engine.this.tags.has(name)) {
                    return null;
                }
                Integer named = this.slots.name(TagKeys.key(name, arguments, Engine.this.modifiers), this.discover);
                if (named == null || this.unclaimed.contains(named)) {
                    return null;
                }
                slot = named;
            } else if (slot >= this.slots.size()) {
                return null;
            }
            Component inserted = this.values == null ? marker(slot) : this.values[slot];
            if (inserted == null) {
                return null;
            }
            this.claimedAny = true;
            return Tag.selfClosingInserting(inserted);
        }

        @Override
        public boolean has(@NotNull String name) {
            return slotTagIndex(name) >= 0 || !Engine.this.tags.has(name);
        }
    }

    // --- rendering ----------------------------------------------------------------------------------------------

    /**
     * @param values the value of each slot: a {@code String} (plain text), a {@code Component}, or null for none
     */
    @NotNull Component render(@NotNull Form form, @Nullable Object @NotNull [] values) {
        return switch (form) {
            case Form.Static(Component component) -> component;
            case Form.Native(List<NativeCompiler.Part> parts, Form.WithValues fallback) -> {
                Component rendered = NativeCompiler.render(parts, values);
                // A gradient over a value with its own style: this render goes through MiniMessage.
                yield rendered != null ? rendered : this.render(Objects.requireNonNull(fallback, "fallback"), values);
            }
            case Form.Markers(Component component, Object spine) -> fill(spine, values);
            // The values as they are make the key (a text's hash is kept by the String; the array is this render's
            // own and is never changed): they only become components when the message has to be parsed.
            case Form.WithValues(String text, SlotSet unclaimed, Slots slots) -> this.withValues.get(
                    new ValuesKey(text, unclaimed, Arrays.asList(values)),
                    key -> this.miniMessage.deserialize(this.legacy(text), new SlotTags(slots, unclaimed, false, components(values))));
        };
    }

    /**
     * The values as components, for MiniMessage's paths: a copy, text as plain text.
     */
    private static @Nullable Component @NotNull [] components(@Nullable Object @NotNull [] values) {
        Component[] components = new Component[values.length];
        for (int i = 0; i < values.length; i++) {
            Object value = values[i];
            components[i] = value instanceof String text ? Component.text(text) : (Component) value;
        }
        return components;
    }

    /**
     * The value of a placeholder: the call's value, the call's value with modifiers ({@code %price.comma%}), a
     * resolver's component or text, a resolver's value with modifiers; or null.
     */
    @Nullable Object value(@NotNull KeyPlan plan, @NotNull Placeholder placeholders, @Nullable Values values,
                           @Nullable Pointered viewer) {
        if (plan.constant != null) {
            return plan.constant;
        }
        // The common case first, small enough to be inlined: a plain value set in these values' own layer. Values
        // placed as written come with raw plans (CompiledTemplate picks them once per render), never here.
        if (values != null && !plan.raw && plan.index >= 0) {
            Object own = values.raw(plan.index);
            if (own instanceof String text && !values.hasFormatted() || own instanceof Component) {
                return own;
            }
        }
        Object value = this.find(plan, placeholders, values, viewer);
        // Raw: a component gives its plain text, and a resolver's text was not parsed (find).
        return plan.raw && value instanceof Component component ? PLAIN.serialize(component) : value;
    }

    private @Nullable Object find(@NotNull KeyPlan plan, @NotNull Placeholder placeholders, @Nullable Values values,
                                  @Nullable Pointered viewer) {
        String key = plan.key;
        String[] bases = plan.bases;
        boolean modifiers = bases.length > 0;
        if (values != null) {
            Object given = this.fromValues(plan, values, viewer);
            if (given != null) {
                return given;
            }
        }
        // A text value is a String, read as it is; any other is a PlaceholderValue.
        Object raw = placeholders.raw(key);
        if (raw instanceof String text) {
            return text;
        }
        if (raw != null) {
            return this.computed(plan, key, (PlaceholderValue) raw, viewer);
        }
        for (int i = 0; i < bases.length; i++) {
            Object base = placeholders.raw(bases[i]);
            if (base != null) {
                // The first key with a value is the one read, whether or not its modifiers apply.
                PlaceholderModifiers.Chain chain = plan.chains[i];
                String text = chain == null ? null : chain.apply(base instanceof String string ? string
                        : this.plainText(bases[i], (PlaceholderValue) base, viewer));
                if (text != null) {
                    return base instanceof PlaceholderValue value && value.isFormatted() ? this.formatted(plan, text, viewer) : text;
                }
                break;
            }
        }
        for (Resolver resolver : this.resolvers) {
            Component component = this.resolveComponent(resolver, key, viewer);
            if (component == FAILED) {
                continue;
            }
            if (component != null) {
                return component;
            }
            String text = this.resolve(resolver, key, viewer);
            if (text != null) {
                return plan.raw ? text : this.parseResolverText(text);
            }
        }
        if (modifiers) {
            for (Resolver resolver : this.resolvers) {
                for (int i = 0; i < bases.length; i++) {
                    // A modifier works on text: a component gives its plain text, and the result is plain text too
                    // (it was never formatted text, so it is not parsed).
                    Component component = this.resolveComponent(resolver, bases[i], viewer);
                    if (component == FAILED) {
                        continue;
                    }
                    String text = component != null ? PLAIN.serialize(component) : this.resolve(resolver, bases[i], viewer);
                    if (text == null) {
                        continue;
                    }
                    PlaceholderModifiers.Chain chain = plan.chains[i];
                    String modified = chain == null ? null : chain.apply(text);
                    if (modified != null) {
                        return component != null || plan.raw ? modified : this.parseResolverText(modified);
                    }
                    break;
                }
            }
        }
        return null;
    }

    /**
     * The value {@link Values} has for a key, read by its place: the key itself, else the first base that is set with
     * its modifiers. Null when it has none, or when the modifiers don't apply.
     */
    private @Nullable Object fromValues(@NotNull KeyPlan plan, @NotNull Values values, @Nullable Pointered viewer) {
        int index = plan.index >= 0 ? plan.index : this.index(plan, plan.index, plan.key);
        Object raw = index < 0 ? null : values.raw(index);
        Values layer = values;
        if (raw == null && index >= 0 && values.parent() != null) {
            layer = values.holder(index);
            raw = layer == null ? null : layer.raw(index);
        }
        if (raw != null) {
            if (raw instanceof String text) {
                return layer.hasFormatted() && layer.isFormatted(index) ? this.formatted(plan, text, viewer) : text;
            }
            if (raw instanceof PlaceholderValue computed) {
                return this.computed(plan, plan.key, computed, viewer);
            }
            return raw;
        }
        int[] indexes = plan.baseIndexes;
        for (int i = 0; i < indexes.length; i++) {
            int baseIndex = this.index(plan, indexes[i], plan.bases[i]);
            layer = baseIndex < 0 ? null : values.holder(baseIndex);
            if (layer != null) {
                PlaceholderModifiers.Chain chain = plan.chains[i];
                if (chain == null) {
                    return null;
                }
                Object base = layer.raw(baseIndex);
                String modified = chain.apply(base instanceof String string ? string
                        : base instanceof PlaceholderValue computed ? this.plainText(plan.bases[i], computed, viewer)
                        : PLAIN.serialize((Component) base));
                boolean formatted = base instanceof String ? layer.isFormatted(baseIndex)
                        : base instanceof PlaceholderValue computed && computed.isFormatted();
                return modified != null && formatted ? this.formatted(plan, modified, viewer) : modified;
            }
        }
        return this.fromLookups(plan, values, viewer);
    }

    /**
     * The value a prefix lookup of the values gives: the key itself, then each base with its modifiers. Null when no
     * lookup of any layer has one.
     */
    private @Nullable Object fromLookups(@NotNull KeyPlan plan, @NotNull Values values, @Nullable Pointered viewer) {
        for (Values layer = values; layer != null; layer = layer.parent()) {
            int count = layer.lookupCount();
            for (int i = 0; i < count; i++) {
                String prefix = layer.lookupPrefix(i);
                if (plan.key.startsWith(prefix)) {
                    String text = this.lookup(layer, i, plan.key, prefix);
                    if (text != null) {
                        return layer.isLookupFormatted(i) ? this.formatted(plan, text, viewer) : text;
                    }
                }
                for (int b = 0; b < plan.bases.length; b++) {
                    if (plan.bases[b].startsWith(prefix)) {
                        String text = this.lookup(layer, i, plan.bases[b], prefix);
                        if (text != null) {
                            PlaceholderModifiers.Chain chain = plan.chains[b];
                            String modified = chain == null ? null : chain.apply(text);
                            if (modified == null) {
                                return null;
                            }
                            return layer.isLookupFormatted(i) ? this.formatted(plan, modified, viewer) : modified;
                        }
                    }
                }
            }
        }
        return null;
    }

    private @Nullable String lookup(@NotNull Values layer, int index, @NotNull String key, @NotNull String prefix) {
        try {
            return layer.lookupFunction(index).apply(key.substring(prefix.length()));
        } catch (RuntimeException e) {
            this.report(key, e);
            return null;
        }
    }

    /**
     * The place of a key in {@link Values}: the one found when the message was compiled, or, for a key declared
     * since, found by its name (a lookup per render, only while the message is older than the key).
     */
    private int index(@NotNull KeyPlan plan, int planned, @NotNull String key) {
        if (planned >= 0 || this.keys.size() == plan.keysSeen) {
            return planned;
        }
        return this.keys.indexOf(key);
    }

    /**
     * A value computed at the render: a component, plain text, or formatted text parsed (as written with raw).
     */
    private @NotNull Object computed(@NotNull KeyPlan plan, @NotNull String key, @NotNull PlaceholderValue value,
                                     @Nullable Pointered viewer) {
        if (value.isComponent()) {
            return this.toComponent(key, value, viewer);
        }
        String text = this.plainText(key, value, viewer);
        return value.isFormatted() ? this.formatted(plan, text, viewer) : text;
    }

    /**
     * Formatted text as a component, parsed once per text (the resolver texts' cache), or as written with raw.
     */
    private @NotNull Object formatted(@NotNull KeyPlan plan, @NotNull String text, @Nullable Pointered viewer) {
        if (plan.raw) {
            return text;
        }
        if (this.placeholdersInFormattedValues && text.indexOf('%') >= 0) {
            text = this.resolveInValue(text, viewer);
        }
        return this.parseResolverText(text);
    }

    /**
     * The {@code %key%} placeholders of a formatted value's text, given by the resolvers for the render's viewer (a
     * resolver's component as its plain text); a placeholder no resolver has stays as written. One level: what a
     * resolver gives is not read again. Modifiers are not read here.
     */
    private @NotNull String resolveInValue(@NotNull String text, @Nullable Pointered viewer) {
        return PlaceholderScanner.replace(text, (key, start) -> {
            if (!isKey(key)) {
                return null;
            }
            for (Resolver resolver : this.resolvers) {
                Component component = this.resolveComponent(resolver, key, viewer);
                if (component == FAILED) {
                    continue;
                }
                if (component != null) {
                    return PLAIN.serialize(component);
                }
                String resolved = this.resolve(resolver, key, viewer);
                if (resolved != null) {
                    return resolved;
                }
            }
            return null;
        });
    }

    @NotNull ValueKeys keys() {
        return this.keys;
    }

    /**
     * How a key is read, found once when its message is compiled: the key itself, then the key before each of its
     * dots (longest first) with the modifiers after it. The modifiers are the ones known at that time.
     */
    static final class KeyPlan {
        private static final String[] NO_BASES = new String[0];
        private static final PlaceholderModifiers.Chain[] NO_CHAINS = new PlaceholderModifiers.Chain[0];

        final String key;
        /**
         * The placeholder as written, {@code %key%}: what shows when it has no value.
         */
        final String written;
        final String[] bases;
        /**
         * For each base, its modifiers, or null when one of them is unknown (the placeholder stays as written).
         */
        final @Nullable PlaceholderModifiers.Chain[] chains;
        /**
         * The place of the key in {@link Values}, and of each base; -1 for a key that was not declared.
         */
        final int index;
        final int[] baseIndexes;
        /**
         * The value, when it never changes ({@code constant} on the builder): a {@code Component}, or the
         * {@code String} its modifiers give. Nothing else is asked then.
         */
        final @Nullable Object constant;
        /**
         * Whether the placeholder ends with the {@code raw} modifier: its value, whatever its source, is placed as
         * plain text, never read as tags or colour codes.
         */
        final boolean raw;
        /**
         * How many keys were declared when this plan was made: a key declared since is looked up by name.
         */
        final int keysSeen;
        /**
         * The same placeholder placed as written, for values that ask for it; made on first use.
         */
        volatile @Nullable KeyPlan rawTwin;

        private KeyPlan(@NotNull String key, @NotNull String written, boolean raw, @NotNull String @NotNull [] bases,
                        @Nullable PlaceholderModifiers.Chain @NotNull [] chains, @NotNull ValueKeys keys,
                        @NotNull Map<String, Component> constants) {
            Object constant = constant(key, bases, chains, constants);
            this.constant = raw && constant instanceof Component component ? PLAIN.serialize(component) : constant;
            this.raw = raw;
            this.keysSeen = keys.size();
            this.key = key;
            this.written = written;
            this.bases = bases;
            this.chains = chains;
            this.index = keys.indexOf(key);
            this.baseIndexes = new int[bases.length];
            for (int i = 0; i < bases.length; i++) {
                this.baseIndexes[i] = keys.indexOf(bases[i]);
            }
        }
    }

    private static @Nullable Object constant(@NotNull String key, @NotNull String @NotNull [] bases,
                                             @Nullable PlaceholderModifiers.Chain @NotNull [] chains,
                                             @NotNull Map<String, Component> constants) {
        Component exact = constants.get(key);
        if (exact != null) {
            return exact;
        }
        for (int i = 0; i < bases.length; i++) {
            Component base = constants.get(bases[i]);
            if (base != null) {
                // With modifiers a constant is its plain text, as any component is. A modifier that does not apply
                // leaves the placeholder to the usual path, which shows it as written.
                return chains[i] == null ? null : chains[i].apply(PLAIN.serialize(base));
            }
        }
        return null;
    }

    @NotNull KeyPlan plan(@NotNull String key) {
        return this.plan(key, false);
    }

    /**
     * The plan of a placeholder in a {@link CompiledText}: every value placed as written, as with raw.
     */
    @NotNull KeyPlan textPlan(@NotNull String key) {
        return this.plan(key, true);
    }

    /**
     * Compiles a text whose placeholders are rendered as a {@code String}.
     */
    public @NotNull fr.robie.minimessageflow.TextTemplate compileText(@NotNull String source) {
        return PlaceholderScanner.hasNested(source) ? new NestedText(this, source) : new CompiledText(this, source);
    }

    /**
     * Hides a {@code %} of a plain value written into a nested text, so the resolvers don't read it; given back after.
     */
    private static final char HIDDEN_PERCENT = '\uF8FF';

    /**
     * The plans of the keys of a nested text, made on first use.
     */
    static final class NestedPlans {
        private final java.util.concurrent.ConcurrentHashMap<String, KeyPlan> plans = new java.util.concurrent.ConcurrentHashMap<>();
    }

    /**
     * A text with a placeholder inside another, written out in the order a string placeholder parser used: each
     * {@code %key%} that has a value is replaced by it (a formatted value as MiniMessage, a plain value with its tags
     * escaped, so what a player typed is never read), then the placeholders these complete are asked of the resolvers
     * (PlaceholderAPI reads its whole argument, tags included), each once. Modifiers apply to the values.
     */
    @NotNull String expandNested(@NotNull String source, @NotNull NestedPlans plans, @Nullable Placeholder placeholders,
                                 @Nullable Values values, @Nullable Pointered viewer) {
        StringBuilder out = null;
        int copied = 0;
        int i = source.indexOf('%');
        while (i >= 0) {
            int end = source.indexOf('%', i + 1);
            if (end < 0) {
                break;
            }
            String key = source.substring(i + 1, end);
            String value = end > i + 1 && isKey(key) ? this.nestedValue(plans, key, placeholders, values, viewer) : null;
            if (value == null) {
                i = end;
                continue;
            }
            if (out == null) {
                out = new StringBuilder(source.length() + 16);
            }
            out.append(source, copied, i).append(value);
            copied = end + 1;
            i = source.indexOf('%', copied);
        }
        String filled = out == null ? source : out.append(source, copied, source.length()).toString();
        String resolved = PlaceholderScanner.replace(filled, (key, start) -> this.resolveNested(key, viewer));
        return resolved.indexOf(HIDDEN_PERCENT) < 0 ? resolved : resolved.replace(HIDDEN_PERCENT, '%');
    }

    /**
     * The value of a key of a nested text from the values and placeholders given, never from the resolvers: a token
     * cut by a nested placeholder ({@code changeoutput_equals_input:}) must not be asked. Null when none has it.
     */
    private @Nullable String nestedValue(@NotNull NestedPlans plans, @NotNull String key, @Nullable Placeholder placeholders,
                                         @Nullable Values values, @Nullable Pointered viewer) {
        KeyPlan plan = plans.plans.computeIfAbsent(key, name -> this.plan(name, false));
        Object value = null;
        if (values != null) {
            value = this.fromValues(plan, values, viewer);
        }
        if (value == null && placeholders != null) {
            Object raw = placeholders.raw(plan.key);
            if (raw instanceof String text) {
                value = text;
            } else if (raw instanceof PlaceholderValue computed) {
                value = this.computed(plan, plan.key, computed, viewer);
            }
        }
        if (value == null) {
            return null;
        }
        if (value instanceof Component component) {
            return this.miniMessage.serialize(component);
        }
        String text = this.miniMessage.escapeTags((String) value);
        return text.indexOf('%') < 0 ? text : text.replace('%', HIDDEN_PERCENT);
    }

    /**
     * A placeholder of a nested text once its values are written in, asked of the resolvers; null to keep it.
     */
    private @Nullable String resolveNested(@NotNull String key, @Nullable Pointered viewer) {
        if (key.isEmpty() || key.indexOf('\n') >= 0) {
            return null;
        }
        for (Resolver resolver : this.resolvers) {
            Component component = this.resolveComponent(resolver, key, viewer);
            if (component == FAILED) {
                continue;
            }
            if (component != null) {
                return this.miniMessage.serialize(component);
            }
            String resolved = this.resolve(resolver, key, viewer);
            if (resolved != null) {
                return resolved;
            }
        }
        return null;
    }

    /**
     * A nested text once written out, formatted (cached by text, as a resolver's text).
     */
    @NotNull Component renderNested(@NotNull String text) {
        return this.parseResolverText(text);
    }

    /**
     * The plan of a placeholder placed as written, made once from the plan as written.
     */
    @NotNull KeyPlan rawPlan(@NotNull KeyPlan plan) {
        KeyPlan twin = plan.rawTwin;
        if (twin == null) {
            twin = this.plan(plan.key, true);
            plan.rawTwin = twin;
        }
        return twin;
    }

    private @NotNull KeyPlan plan(@NotNull String key, boolean forceRaw) {
        String written = "%" + key + "%";
        boolean raw = key.length() > RAW_SUFFIX.length() && key.endsWith(RAW_SUFFIX);
        if (raw) {
            // The raw modifier changes how the value is placed, not its text: the key is read without it.
            key = key.substring(0, key.length() - RAW_SUFFIX.length());
        }
        raw |= forceRaw;
        List<String> bases = new ArrayList<>(2);
        List<PlaceholderModifiers.Chain> chains = new ArrayList<>(2);
        for (int dot = key.lastIndexOf('.'); dot > 0; dot = key.lastIndexOf('.', dot - 1)) {
            bases.add(key.substring(0, dot));
            chains.add(this.modifiers.compile(key.substring(dot + 1)));
        }
        if (this.legacyPrefixes) {
            for (int i = 0; i < LEGACY_PREFIXES.length; i++) {
                String prefix = LEGACY_PREFIXES[i];
                if (key.length() > prefix.length() && key.startsWith(prefix)) {
                    bases.add(key.substring(prefix.length()));
                    chains.add(i == CAPITALIZE_PREFIX ? CAPITALIZE_FIRST : this.modifiers.compile(LEGACY_MODIFIERS[i]));
                    break;
                }
            }
        }
        if (this.emptyOnFailedModifier) {
            for (int i = 0; i < chains.size(); i++) {
                PlaceholderModifiers.Chain chain = chains.get(i);
                chains.set(i, chain == null ? PlaceholderModifiers.Chain.alwaysEmpty() : chain.orEmpty());
            }
        }
        return bases.isEmpty() ? new KeyPlan(key, written, raw, KeyPlan.NO_BASES, KeyPlan.NO_CHAINS, this.keys, this.constants)
                : new KeyPlan(key, written, raw, bases.toArray(KeyPlan.NO_BASES), chains.toArray(KeyPlan.NO_CHAINS), this.keys,
                this.constants);
    }

    /**
     * The plain text of a value, for a modifier: the text of a text value as it is, without a component in between.
     */
    private @NotNull String plainText(@NotNull String key, @NotNull PlaceholderValue value, @Nullable Pointered viewer) {
        if (value.isComponent()) {
            return PLAIN.serialize(this.toComponent(key, value, viewer));
        }
        try {
            return value.evaluate(viewer);
        } catch (Exception e) {
            this.report(key, e);
            return "%" + key + "%";
        }
    }

    /**
     * The plain text of a value written into a tag argument.
     */
    static @NotNull String inlineText(@NotNull KeyPlan plan, @Nullable Object value, boolean colonBreaking) {
        if (value == null) {
            return plan.written;
        }
        return stripTagBreaking(value instanceof String text ? text : PLAIN.serialize((Component) value), colonBreaking);
    }

    /**
     * A resolver that throws has no value for the key; the error handler is told.
     */
    private @Nullable String resolve(@NotNull Resolver resolver, @NotNull String key, @Nullable Pointered viewer) {
        try {
            return resolver.resolve(key, viewer);
        } catch (RuntimeException e) {
            this.report(key, e);
            return null;
        }
    }

    /**
     * @return the component, null when the resolver has none for the key, or {@link #FAILED} when it threw: the
     * error handler was told, and the resolver's text is not asked for this key (it would fail, and be told, again)
     */
    private @Nullable Component resolveComponent(@NotNull Resolver resolver, @NotNull String key, @Nullable Pointered viewer) {
        try {
            return resolver.resolveComponent(key, viewer);
        } catch (RuntimeException e) {
            this.report(key, e);
            return FAILED;
        }
    }

    /**
     * The message the preprocessor gives; the message as it is when it throws or gives null (the error handler is told).
     */
    private @NotNull String preprocess(@NotNull String source) {
        try {
            String processed = this.preprocessor.apply(source);
            return processed == null ? source : processed;
        } catch (RuntimeException e) {
            this.report("preprocessor", e);
            return source;
        }
    }

    private void report(@NotNull String key, @NotNull Throwable error) {
        try {
            this.errors.accept(key, error);
        } catch (RuntimeException ignored) {
            // A handler that throws must not break a render.
        }
    }

    private @NotNull Component parseResolverText(@NotNull String text) {
        return this.resolverTexts.get(text, this.parseText);
    }

    private @NotNull Component parseNewText(@NotNull String text) {
        return this.miniMessage.deserialize(this.legacy(stripNoncharacters(text)));
    }

    /**
     * A value that throws shows as the placeholder was written; the error handler is told.
     */
    private @NotNull Component toComponent(@NotNull String key, @NotNull PlaceholderValue value, @Nullable Pointered viewer) {
        try {
            return value.asComponent(viewer);
        } catch (Exception e) {
            this.report(key, e);
            return Component.text("%" + key + "%");
        }
    }

    private @NotNull String legacy(@NotNull String text) {
        return this.legacyCodes ? LegacyCodes.toMiniMessage(text, this.bareHex) : text;
    }

    // --- markers ------------------------------------------------------------------------------------------------

    private static @NotNull Component marker(int slot) {
        return Component.text(MARKER_START + String.valueOf(slot) + MARKER_END);
    }

    private boolean hasMarker(@NotNull Component component) {
        if (markerIndex(component) >= 0) {
            return true;
        }
        for (Component child : component.children()) {
            if (this.hasMarker(child)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether the tree has a virtual component ({@code <gradient>}, {@code <rainbow>}): it keeps its own copy of what
     * it wraps, where a marker can't be swapped (and it has no {@code equals} to compare).
     */
    private boolean hasVirtual(@NotNull Component component) {
        if (this.bridge.isVirtual(component)) {
            return true;
        }
        for (Component child : component.children()) {
            if (this.hasVirtual(child)) {
                return true;
            }
        }
        return false;
    }

    /**
     * A component above a marker: itself without its children, and its children in order, each a {@code Component}
     * (nothing to fill in it, compacted once), a {@code Spine}, or an {@code Integer} (the slot of a marker).
     */
    private record Spine(@NotNull Component shell, @NotNull Object @NotNull [] children) {
    }

    /**
     * A parsed message ready to be filled: only the components above a marker are kept apart, so a render copies
     * those and takes everything else as it is, without looking for the markers again or compacting.
     *
     * @return a {@link Spine}, the slot of a marker ({@code Integer}), or the component itself when it has no marker
     */
    private static @NotNull Object spine(@NotNull Component component) {
        int marker = markerIndex(component);
        if (marker >= 0) {
            return marker;
        }
        List<Component> children = component.children();
        Object[] built = null;
        for (int i = 0; i < children.size(); i++) {
            Object child = spine(children.get(i));
            if (built == null && !(child instanceof Component)) {
                built = new Object[children.size()];
                for (int before = 0; before < i; before++) {
                    built[before] = children.get(before).compact();
                }
            }
            if (built != null) {
                built[i] = child instanceof Component fixed ? fixed.compact() : child;
            }
        }
        if (built == null) {
            return component;
        }
        // A component that is nothing itself (MiniMessage's wrappers) gives its place to its children: fewer
        // components in every render.
        List<Object> flat = new ArrayList<>(built.length);
        for (Object child : built) {
            if (child instanceof Spine(Component shell, Object[] inner) && isNothing(shell)) {
                flat.addAll(Arrays.asList(inner));
            } else if (child instanceof Component fixed && isNothing(fixed.children(List.of())) && !fixed.children().isEmpty()) {
                flat.addAll(fixed.children());
            } else if (!(child instanceof Component fixed) || !fixed.equals(Component.empty())) {
                flat.add(child);
            }
        }
        Component shell = component.children(List.of());
        return flat.size() == 1 && isNothing(shell) ? flat.getFirst() : new Spine(shell, flat.toArray());
    }

    /**
     * Whether a component without its children shows nothing and gives its children nothing: empty text, no style.
     */
    private static boolean isNothing(@NotNull Component shell) {
        return shell.equals(Component.empty());
    }

    private static @NotNull Component fill(@NotNull Object node, @Nullable Object @NotNull [] values) {
        if (node instanceof Spine(Component shell, Object[] children)) {
            Component[] filled = new Component[children.length];
            for (int i = 0; i < children.length; i++) {
                Object child = children[i];
                filled[i] = child instanceof Component fixed ? fixed : fill(child, values);
            }
            return Children.under(shell, Children.of(filled));
        }
        if (node instanceof Integer slot) {
            Object value = slot < values.length ? values[slot] : null;
            return value == null ? Component.empty() : value instanceof String text ? Component.text(text) : (Component) value;
        }
        return (Component) node;
    }

    private @NotNull Component insert(@NotNull Component component, @Nullable Component @NotNull [] values) {
        int index = markerIndex(component);
        if (index >= 0) {
            Component value = index < values.length ? values[index] : null;
            return value != null ? value : Component.empty();
        }
        List<Component> children = component.children();
        List<Component> replaced = null;
        for (int i = 0; i < children.size(); i++) {
            Component child = children.get(i);
            Component inserted = this.insert(child, values);
            if (inserted != child) {
                if (replaced == null) {
                    replaced = new ArrayList<>(children);
                }
                replaced.set(i, inserted);
            }
        }
        return replaced != null ? component.children(replaced) : component;
    }

    private static int markerIndex(@NotNull Component component) {
        if (!(component instanceof TextComponent text) || !component.children().isEmpty() || !component.style().isEmpty()) {
            return -1;
        }
        String content = text.content();
        int length = content.length();
        if (length < 3 || content.charAt(0) != MARKER_START || content.charAt(length - 1) != MARKER_END) {
            return -1;
        }
        return parseIndex(content, 1, length - 1);
    }

    static int slotTagIndex(@NotNull String name) {
        return name.startsWith(SLOT_TAG) ? parseIndex(name, SLOT_TAG.length(), name.length()) : -1;
    }

    static int parseIndex(@NotNull String s, int from, int to) {
        if (from >= to || to - from > 6) {
            return -1;
        }
        int value = 0;
        for (int i = from; i < to; i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') {
                return -1;
            }
            value = value * 10 + (c - '0');
        }
        return value;
    }

    // --- text ---------------------------------------------------------------------------------------------------

    /**
     * Whether a token can be a placeholder key: {@link PlaceholderScanner#isKey}, the rule a declared key's name
     * follows too.
     */
    static boolean isKey(@NotNull String key) {
        return PlaceholderScanner.isKey(key);
    }

    /**
     * Removes the characters that could end a tag or its quotes, or be read again as a placeholder or a colour; and
     * {@code :}, which would start another argument, when the value is not in quotes.
     */
    private static @NotNull String stripTagBreaking(@NotNull String text, boolean colonBreaking) {
        StringBuilder out = null;
        for (int i = 0, length = text.length(); i < length; i++) {
            char c = text.charAt(i);
            boolean breaking = c == '<' || c == '>' || c == '\'' || c == '"' || c == '\\' || c == '%' || c == '&' || c == '§'
                    || c == ':' && colonBreaking;
            if (breaking && out == null) {
                out = new StringBuilder(length).append(text, 0, i);
            } else if (!breaking && out != null) {
                out.append(c);
            }
        }
        return out != null ? out.toString() : text;
    }

    /**
     * Removes the noncharacters the engine uses as markers, so a value can only be swapped where a placeholder is.
     */
    private static @NotNull String stripNoncharacters(@NotNull String text) {
        StringBuilder out = null;
        for (int i = 0, length = text.length(); i < length; i++) {
            char c = text.charAt(i);
            boolean marker = c >= MARKER_START && c <= INLINE_END;
            if (marker && out == null) {
                out = new StringBuilder(length).append(text, 0, i);
            } else if (!marker && out != null) {
                out.append(c);
            }
        }
        return out != null ? out.toString() : text;
    }

    /**
     * Splits a text at its inline markers: strings, and the inline value number of each marker.
     */
    private static @NotNull List<Object> pieces(@NotNull String tagged) {
        List<Object> pieces = new ArrayList<>();
        int from = 0;
        int start;
        while ((start = tagged.indexOf(INLINE_START, from)) >= 0) {
            int end = tagged.indexOf(INLINE_END, start);
            if (start > from) {
                pieces.add(tagged.substring(from, start));
            }
            pieces.add(parseIndex(tagged, start + 1, end));
            from = end + 1;
        }
        if (from < tagged.length()) {
            pieces.add(tagged.substring(from));
        }
        return List.copyOf(pieces);
    }

    static @NotNull String join(@NotNull List<Object> pieces, @Nullable String @NotNull [] inline) {
        StringBuilder out = new StringBuilder(64);
        for (Object piece : pieces) {
            if (piece instanceof Integer index) {
                String value = inline[index];
                out.append(value != null ? value : "");
            } else {
                out.append((String) piece);
            }
        }
        return out.toString();
    }

    // --- data ---------------------------------------------------------------------------------------------------

    /**
     * How a compiled message renders.
     */
    sealed interface Form {
        /**
         * No placeholder: always this component.
         */
        record Static(@NotNull Component component) implements Form {
        }

        /**
         * Compiled without MiniMessage: fixed parts and values (stage 2).
         */
        record Native(@NotNull List<NativeCompiler.Part> parts, @Nullable WithValues fallback) implements Form {
        }

        /**
         * Markers swapped for the values.
         *
         * @param component what MiniMessage parsed, with the markers
         * @param spine     the same, ready to fill: see {@link Engine#spine}
         */
        record Markers(@NotNull Component component, @NotNull Object spine) implements Form {
        }

        /**
         * Parsed with the values, cached by values.
         */
        record WithValues(@NotNull String text, @NotNull SlotSet unclaimed, @NotNull Slots slots) implements Form {
        }
    }

    private record ValuesKey(@NotNull String text, @NotNull SlotSet unclaimed, @NotNull List<Object> values) {
    }

    private record InlineKey(@NotNull String text, @NotNull SlotSet unclaimed) {
    }

    /**
     * The placeholders of a message, by slot number: {@code %key%} ones, then {@code <key>} ones as parsing finds
     * them. Only changed while the message is compiled, read-only after {@link #freeze()}.
     */
    static final class Slots {
        private final List<String> keys = new ArrayList<>(4);
        private final List<Boolean> named = new ArrayList<>(4);
        private final Map<String, Integer> percent = new HashMap<>(4);
        private final Map<String, Integer> names = new HashMap<>(4);
        private boolean frozen;

        /**
         * A copy that can grow apart from this one.
         */
        @NotNull Slots copy() {
            Slots copy = new Slots();
            copy.keys.addAll(this.keys);
            copy.named.addAll(this.named);
            copy.percent.putAll(this.percent);
            copy.names.putAll(this.names);
            return copy;
        }

        int percent(@NotNull String key) {
            return this.percent.computeIfAbsent(key, k -> this.add(k, false));
        }

        @Nullable Integer name(@NotNull String key, boolean discover) {
            Integer slot = this.names.get(key);
            if (slot == null && discover && !this.frozen) {
                slot = this.add(key, true);
                this.names.put(key, slot);
            }
            return slot;
        }

        private int add(@NotNull String key, boolean name) {
            this.keys.add(key);
            this.named.add(name);
            return this.keys.size() - 1;
        }

        void freeze() {
            this.frozen = true;
        }

        int size() {
            return this.keys.size();
        }

        @NotNull String key(int slot) {
            return this.keys.get(slot);
        }

        boolean isName(int slot) {
            return this.named.get(slot);
        }

        int variantLimit() {
            return MAX_VARIANTS;
        }
    }
}
