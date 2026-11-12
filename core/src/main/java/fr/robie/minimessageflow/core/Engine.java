package fr.robie.minimessageflow.core;

import fr.robie.minimessageflow.Placeholder;
import fr.robie.minimessageflow.ModifierSet;
import fr.robie.minimessageflow.PlaceholderValue;
import fr.robie.minimessageflow.Resolver;
import fr.robie.minimessageflow.Template;
import fr.robie.minimessageflow.cache.Cache;
import fr.robie.minimessageflow.cache.CacheFactory;
import fr.robie.minimessageflow.internal.PlaceholderScanner;
import net.kyori.adventure.pointer.Pointered;
import net.kyori.adventure.text.Component;
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
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    private final boolean legacyCodes;
    private final boolean nativeCompiler;
    private final boolean verifyNative;
    private final List<Resolver> resolvers;
    private final @Nullable TagResolver userTags;
    private final TagResolver tags;
    private final MiniMessage miniMessage;
    private final MiniMessage templateMiniMessage;
    private final AdventureBridge bridge;
    private final Cache<String, Component> resolverTexts;
    private final Cache<ValuesKey, Component> withValues;
    private final Cache<InlineKey, Form> inlineForms;
    private final BiConsumer<String, Throwable> errors;
    private final ModifierSet modifiers;
    private final boolean bareHex;

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
     */
    public Engine(boolean legacyCodes, @NotNull List<Resolver> resolvers, @Nullable TagResolver userTags, int cacheSize,
                  boolean nativeCompiler, boolean verifyNative, @NotNull CacheFactory caches,
                  @NotNull BiConsumer<String, Throwable> errors, @NotNull ModifierSet modifiers, boolean bareHex) {
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
    }

    public void clearCache() {
        this.resolverTexts.clear();
        this.withValues.clear();
        this.inlineForms.clear();
    }

    // --- compiling ----------------------------------------------------------------------------------------------

    public @NotNull Template compile(@NotNull String source) {
        String text = stripNoncharacters(source);
        Slots slots = new Slots();
        List<String> inlineKeys = new ArrayList<>(0);
        int[] tags = text.indexOf('%') < 0 ? NO_TAGS : PlaceholderScanner.tagRanges(text);
        String tagged = PlaceholderScanner.replace(text, (key, start) -> {
            if (!isKey(key)) {
                return null;
            }
            if (PlaceholderScanner.inTag(tags, start)) {
                inlineKeys.add(key);
                return INLINE_START + String.valueOf(inlineKeys.size() - 1) + INLINE_END;
            }
            return "<" + SLOT_TAG + slots.percent(key) + ">";
        });
        List<Object> pieces = inlineKeys.isEmpty() ? null : pieces(tagged);
        String compiled = pieces == null ? tagged : join(pieces, new String[inlineKeys.size()]);
        Template compiledNatively = this.compileNatively(source, tagged, compiled, slots, pieces, inlineKeys);
        if (compiledNatively != null) {
            return compiledNatively;
        }
        // Finds the <key> slots; for a message without inline values, this is also its form.
        Form base = this.compileForm(compiled, slots, Set.of(), true);
        slots.freeze();
        Form.Native inline = pieces == null ? null : this.compileInline(tagged, pieces, inlineKeys.size(), slots);
        return new CompiledTemplate(this, source, compiled, slots, pieces, List.copyOf(inlineKeys), base, inline);
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
                                               @NotNull Slots slots, @Nullable List<Object> pieces, @NotNull List<String> inlineKeys) {
        if (!this.nativeCompiler) {
            return null;
        }
        Slots trial = slots.copy();
        List<NativeCompiler.Part> parts = NativeCompiler.compile(this.legacy(tagged), StandardTags.defaults(), this.userTags, this.modifiers,
                trial, Set.of(), true);
        if (parts == null) {
            return null;
        }
        trial.freeze();
        if (this.verifyNative) {
            boolean transform = NativeCompiler.hasTransform(parts);
            for (boolean longer : transform ? new boolean[]{false, true} : new boolean[]{true}) {
                Component[] probe = this.probe(trial, Set.of(), longer);
                String[] inline = new String[inlineKeys.size()];
                for (int i = 0; i < inline.length; i++) {
                    inline[i] = PROBE + "i" + i + (longer ? "-longer probe value" : "") + PROBE;
                }
                String text = pieces == null ? compiled : join(pieces, inline);
                Component reparsed = this.miniMessage.deserialize(this.legacy(text), new SlotTags(trial, Set.of(), false, probe));
                if (!same(NativeCompiler.render(parts, probe, inline), reparsed)) {
                    return null;
                }
            }
        }
        List<String> inlineKeysCopy = List.copyOf(inlineKeys);
        if (pieces != null) {
            Form.Native inline = new Form.Native(parts, null);
            return new CompiledTemplate(this, source, compiled, trial, pieces, inlineKeysCopy, inline, inline);
        }
        Form base = trial.size() == 0
                ? new Form.Static(Objects.requireNonNull(NativeCompiler.render(parts, new Component[0])))
                : new Form.Native(parts, new Form.WithValues(compiled, Set.of(), trial));
        return new CompiledTemplate(this, source, compiled, trial, null, inlineKeysCopy, base, null);
    }

    @NotNull Form compileForm(@NotNull String text, @NotNull Slots slots, @NotNull Set<Integer> unclaimed, boolean discover) {
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
        return swappable ? new Form.Markers(parsed) : withValues;
    }

    /**
     * Unique plain values for the slots, short (as long as a marker) or long.
     */
    private @Nullable Component @NotNull [] probe(@NotNull Slots slots, @NotNull Set<Integer> unclaimed, boolean longer) {
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
                slots, Set.of());
        if (parts == null) {
            return null;
        }
        if (this.verifyNative) {
            for (boolean longer : new boolean[]{false, true}) {
                Component[] probe = this.probe(slots, Set.of(), longer);
                String[] inline = new String[inlineCount];
                for (int i = 0; i < inlineCount; i++) {
                    inline[i] = PROBE + "i" + i + (longer ? "-longer probe value" : "") + PROBE;
                }
                Component reparsed = this.miniMessage.deserialize(this.legacy(join(pieces, inline)), new SlotTags(slots, Set.of(), false, probe));
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
    @Nullable Component renderInline(@NotNull Form.Native form, @Nullable Component @NotNull [] values, @NotNull String @NotNull [] inline) {
        return NativeCompiler.render(form.parts(), values, inline);
    }

    @NotNull Form inlineForm(@NotNull String text, @NotNull Slots slots, @NotNull Set<Integer> unclaimed) {
        return this.inlineForms.get(new InlineKey(text, unclaimed), key -> this.compileForm(text, slots, unclaimed, false));
    }

    /**
     * Tags that become slots: {@code <mmf_slot_N>}, and any tag that no standard or user tag claims.
     */
    private final class SlotTags implements TagResolver {
        private final Slots slots;
        private final Set<Integer> unclaimed;
        private final boolean discover;
        /**
         * The values to insert, or null to insert markers.
         */
        private final @Nullable Component @Nullable [] values;
        /**
         * Whether a slot was claimed at least once. Not a count: MiniMessage resolves a tag several times.
         */
        boolean claimedAny;

        SlotTags(@NotNull Slots slots, @NotNull Set<Integer> unclaimed, boolean discover, @Nullable Component @Nullable [] values) {
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

    @NotNull Component render(@NotNull Form form, @Nullable Component @NotNull [] values) {
        return switch (form) {
            case Form.Static(Component component) -> component;
            case Form.Native(List<NativeCompiler.Part> parts, Form.WithValues fallback) -> {
                Component rendered = NativeCompiler.render(parts, values);
                // A gradient over a value with its own style: this render goes through MiniMessage.
                yield rendered != null ? rendered : this.render(Objects.requireNonNull(fallback, "fallback"), values);
            }
            case Form.Markers(Component component) -> this.insert(component, values).compact();
            case Form.WithValues(String text, Set<Integer> unclaimed, Slots slots) -> this.withValues.get(
                    new ValuesKey(text, unclaimed, Arrays.asList(values.clone())),
                    key -> this.miniMessage.deserialize(this.legacy(text), new SlotTags(slots, unclaimed, false, values)));
        };
    }

    /**
     * The value of a placeholder: the call's value, the call's value with modifiers ({@code %price.comma%}), a
     * resolver's text, a resolver's text with modifiers; or null.
     */
    @Nullable Component value(@NotNull String key, @NotNull Placeholder placeholders, @Nullable Pointered viewer) {
        PlaceholderValue value = placeholders.get(key);
        if (value != null) {
            return this.toComponent(key, value, viewer);
        }
        boolean modifiers = key.indexOf('.') > 0;
        if (modifiers) {
            String text = this.modifiers.resolve(key, k -> {
                PlaceholderValue base = placeholders.get(k);
                return base == null ? null : PLAIN.serialize(this.toComponent(k, base, viewer));
            });
            if (text != null) {
                return Component.text(text);
            }
        }
        for (Resolver resolver : this.resolvers) {
            String text = this.resolve(resolver, key, viewer);
            if (text != null) {
                return this.parseResolverText(text);
            }
        }
        if (modifiers) {
            for (Resolver resolver : this.resolvers) {
                String text = this.modifiers.resolve(key, k -> k.equals(key) ? null : this.resolve(resolver, k, viewer));
                if (text != null) {
                    return this.parseResolverText(text);
                }
            }
        }
        return null;
    }

    /**
     * The plain text of a value written into a tag argument.
     */
    static @NotNull String inlineText(@NotNull String key, @Nullable Component value) {
        return value == null ? "%" + key + "%" : stripTagBreaking(PLAIN.serialize(value));
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

    private void report(@NotNull String key, @NotNull Throwable error) {
        try {
            this.errors.accept(key, error);
        } catch (RuntimeException ignored) {
            // A handler that throws must not break a render.
        }
    }

    private @NotNull Component parseResolverText(@NotNull String text) {
        return this.resolverTexts.get(text, t -> this.miniMessage.deserialize(this.legacy(stripNoncharacters(t))));
    }

    /**
     * A value that throws shows as the placeholder was written; the error handler is told.
     */
    private @NotNull Component toComponent(@NotNull String key, @NotNull PlaceholderValue value, @Nullable Pointered viewer) {
        if (value instanceof PlaceholderValue.ComponentValue(Component component)) {
            return component;
        }
        try {
            return Component.text(value.evaluate(viewer));
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
     * Whether a token can be a placeholder key: no whitespace and none of the characters a tag, an escape or a
     * legacy code uses. Such a token is never resolved, so the scan finds the same tokens whatever the values.
     */
    private static boolean isKey(@NotNull String key) {
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if (Character.isWhitespace(c) || c == '<' || c == '>' || c == '&' || c == '§' || c == '\\' || c == '\''
                    || c == '"' || c == '#') {
                return false;
            }
        }
        return true;
    }

    /**
     * Removes the characters that could end a tag or its quotes, or be read again as a placeholder or a colour.
     */
    private static @NotNull String stripTagBreaking(@NotNull String text) {
        StringBuilder out = null;
        for (int i = 0, length = text.length(); i < length; i++) {
            char c = text.charAt(i);
            boolean breaking = c == '<' || c == '>' || c == '\'' || c == '"' || c == '\\' || c == '%' || c == '&' || c == '§';
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
         */
        record Markers(@NotNull Component component) implements Form {
        }

        /**
         * Parsed with the values, cached by values.
         */
        record WithValues(@NotNull String text, @NotNull Set<Integer> unclaimed, @NotNull Slots slots) implements Form {
        }
    }

    private record ValuesKey(@NotNull String text, @NotNull Set<Integer> unclaimed, @NotNull List<Component> values) {
    }

    private record InlineKey(@NotNull String text, @NotNull Set<Integer> unclaimed) {
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
