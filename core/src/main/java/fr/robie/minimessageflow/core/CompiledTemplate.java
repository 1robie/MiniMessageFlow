package fr.robie.minimessageflow.core;

import fr.robie.minimessageflow.Placeholder;
import fr.robie.minimessageflow.Template;
import fr.robie.minimessageflow.Values;
import net.kyori.adventure.pointer.Pointered;
import net.kyori.adventure.text.Component;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * A message compiled by {@link Engine}. Immutable but for its cache of variants (one per set of unknown
 * {@code <key>} placeholders), which is thread-safe.
 */
@ApiStatus.Internal
final class CompiledTemplate implements Template {

    private final Engine engine;
    private final String source;
    /**
     * The text MiniMessage parses: slot tags in place of {@code %key%}, inline values left out.
     */
    private final String compiled;
    private final Engine.Slots slots;
    /**
     * The compiled text split at its inline values (strings and inline numbers), or null when it has none.
     */
    private final @Nullable List<Object> pieces;
    private final List<String> inlineKeys;
    /**
     * A key is evaluated once per render, wherever it is written (a supplier may give another value each time).
     * For each slot, the first slot with the same key (itself when it is the first).
     */
    private final int[] slotSources;
    /**
     * For each inline value, where a render already has it: the slot with the same key, or {@code -1 - i} for the
     * first inline value {@code i} with the same key (itself when it is the first).
     */
    private final int[] inlineSources;
    /**
     * How each slot's key and each inline value's key is read, found once.
     */
    private final Engine.KeyPlan[] slotPlans;
    private final Engine.KeyPlan[] inlinePlans;
    private final Engine.Form base;
    /**
     * The message with values in a click or insert argument, compiled once (stage 3), or null.
     */
    private final @Nullable Engine.Form.Native inline;
    private final boolean isStatic;
    /**
     * Why the native compiler gave the message up, when it did.
     */
    private final @Nullable String reason;
    /**
     * What every render gives, when nothing in the message changes: no placeholder, or only constants.
     */
    private final @Nullable Component fixed;
    private final Kind kind;
    private final List<String> placeholders;
    private final ConcurrentHashMap<Set<Integer>, Engine.Form> variants = new ConcurrentHashMap<>();
    /**
     * The last render, given again while the values are the same: a message sent to many players with the same
     * values, a line drawn again with nothing changed. Not volatile: a thread that sees an older one only misses.
     * Alone on its cache lines: when one thread keeps a render, the others read again this field, not the rest.
     */
    private final Padded<Last> last = new Padded<>();
    /**
     * After a miss, a render is kept about once in this many when the last one kept was given again at least once...
     */
    private static final int KEEP_ONE_IN = 8;
    /**
     * ...and once in this many when it never was: values that change at each render then seldom write a field every
     * thread reads (8 threads on one template: such a write costs more than the render).
     */
    private static final int KEEP_RARELY = 64;
    private static final Object[] NO_VALUES = new Object[0];

    CompiledTemplate(@NotNull Engine engine, @NotNull String source, @NotNull String compiled, @NotNull Engine.Slots slots,
                     @Nullable List<Object> pieces, @NotNull List<String> inlineKeys, @NotNull Engine.Form base,
                     @Nullable Engine.Form.Native inline, @Nullable String reason) {
        this.reason = reason;
        this.engine = engine;
        this.source = source;
        this.compiled = compiled;
        this.slots = slots;
        this.pieces = pieces;
        this.inlineKeys = inlineKeys;
        this.slotSources = new int[slots.size()];
        this.slotPlans = new Engine.KeyPlan[slots.size()];
        for (int slot = 0; slot < this.slotSources.length; slot++) {
            this.slotSources[slot] = firstSlot(slots, slots.key(slot));
            this.slotPlans[slot] = engine.plan(slots.key(slot));
        }
        this.inlineSources = new int[inlineKeys.size()];
        this.inlinePlans = new Engine.KeyPlan[inlineKeys.size()];
        for (int i = 0; i < this.inlineSources.length; i++) {
            String key = inlineKeys.get(i);
            this.inlinePlans[i] = engine.plan(key);
            int slot = firstSlot(slots, key);
            this.inlineSources[i] = slot >= 0 ? slot : -1 - inlineKeys.indexOf(key);
        }
        this.base = base;
        this.inline = inline;
        boolean constant = this.slotPlans.length + this.inlinePlans.length > 0;
        for (Engine.KeyPlan plan : this.slotPlans) {
            constant &= plan.constant != null;
        }
        for (Engine.KeyPlan plan : this.inlinePlans) {
            constant &= plan.constant != null;
        }
        if (constant) {
            // Every placeholder is a constant of the instance: rendered here, once.
            this.fixed = this.render(Placeholder.empty(), null, null);
        } else {
            this.fixed = pieces == null && slots.size() == 0 && base instanceof Engine.Form.Static(Component component) ? component : null;
        }
        this.isStatic = this.fixed != null;
        if (this.fixed != null) {
            this.kind = Kind.STATIC;
        } else if (pieces != null) {
            this.kind = inline != null ? Kind.NATIVE : Kind.PARSED;
        } else {
            this.kind = switch (base) {
                case Engine.Form.Static ignored -> Kind.STATIC;
                case Engine.Form.Native ignored -> Kind.NATIVE;
                case Engine.Form.Markers ignored -> Kind.MARKERS;
                case Engine.Form.WithValues ignored -> Kind.PARSED;
            };
        }
        Set<String> keys = new java.util.LinkedHashSet<>();
        for (int slot = 0; slot < slots.size(); slot++) {
            keys.add(slots.key(slot));
        }
        keys.addAll(inlineKeys);
        this.placeholders = List.copyOf(keys);
    }

    private static int firstSlot(@NotNull Engine.Slots slots, @NotNull String key) {
        for (int slot = 0; slot < slots.size(); slot++) {
            if (slots.key(slot).equals(key)) {
                return slot;
            }
        }
        return -1;
    }

    @Override
    public @NotNull String source() {
        return this.source;
    }

    @Override
    public boolean isStatic() {
        return this.isStatic;
    }

    @Override
    public @NotNull Kind kind() {
        return this.kind;
    }

    @Override
    public @Nullable String fallbackReason() {
        return this.kind == Kind.MARKERS || this.kind == Kind.PARSED ? this.reason : null;
    }

    @Override
    public @NotNull List<String> placeholders() {
        return this.placeholders;
    }

    @Override
    public @NotNull Component render(@NotNull Placeholder placeholders, @Nullable Pointered viewer) {
        if (this.fixed != null) {
            return this.fixed;
        }
        return this.render(placeholders, null, viewer);
    }

    @Override
    public @NotNull Component render(@NotNull Values values, @Nullable Pointered viewer) {
        if (values.keys() != this.engine.keys()) {
            throw new IllegalArgumentException("these values were made by another MiniMessageFlow");
        }
        if (this.fixed != null) {
            return this.fixed;
        }
        return this.render(Placeholder.empty(), values, viewer);
    }

    private @NotNull Component render(@NotNull Placeholder placeholders, @Nullable Values fast, @Nullable Pointered viewer) {
        int size = this.slots.size();
        // While every value is the last render's, nothing is allocated: the arrays are made at the first that differs,
        // and a render with the same values gives the last component again. Each value a String (plain text), a
        // Component, or null.
        Last last = this.last.get();
        Object[] keptValues = last == null ? null : last.values;
        Object[] values = null;
        Set<Integer> unknown = null;
        for (int slot = 0; slot < size; slot++) {
            int source = this.slotSources[slot];
            Object value = source != slot ? this.given(values, keptValues, source)
                    : this.engine.value(this.slotPlans[slot], placeholders, fast, viewer);
            if (value == null) {
                if (this.slots.isName(slot)) {
                    if (unknown == null) {
                        unknown = new HashSet<>(4);
                    }
                    unknown.add(slot);
                } else {
                    value = this.slotPlans[slot].written;
                }
            }
            if (values == null) {
                if (keptValues != null && Last.same(keptValues[slot], value)) {
                    continue;
                }
                values = new Object[size];
                if (keptValues != null) {
                    System.arraycopy(keptValues, 0, values, 0, slot);
                }
            }
            values[slot] = value;
        }
        String[] inline = null;
        boolean inlineSame = true;
        if (this.pieces != null) {
            String[] keptInline = last == null ? null : last.inline;
            int count = this.inlineKeys.size();
            for (int i = 0; i < count; i++) {
                int source = this.inlineSources[i];
                String text;
                if (source < 0 && -1 - source < i) {
                    text = inline != null ? inline[-1 - source] : keptInline[-1 - source];
                } else {
                    Object value = source >= 0 ? this.given(values, keptValues, source)
                            : this.engine.value(this.inlinePlans[i], placeholders, fast, viewer);
                    text = Engine.inlineText(this.inlinePlans[i], value);
                }
                if (inline == null) {
                    if (keptInline != null && keptInline[i].equals(text)) {
                        continue;
                    }
                    inline = new String[count];
                    if (keptInline != null) {
                        System.arraycopy(keptInline, 0, inline, 0, i);
                    }
                }
                inline[i] = text;
            }
            if (inline == null) {
                // Every text as the last render's: its array, which is never changed.
                inline = keptInline;
            } else {
                inlineSame = false;
            }
        }
        // Every value has been asked for (a supplier, a resolver): what is built from them depends on nothing else.
        if (values == null && inlineSame && last != null) {
            if (!last.reused) {
                // Written once per kept render: renders of this template do come again.
                last.reused = true;
            }
            return last.component;
        }
        if (values == null) {
            // The same values, other inline texts: the last render's values, which are never changed.
            values = keptValues != null ? keptValues : NO_VALUES;
        }
        Component rendered = this.build(values, unknown, inline);
        // Kept after about one miss in eight while kept renders are given again (values that come again are kept within
        // a few renders), one in 64 while they are not (values that change at each render). The arrays are this
        // render's own and are never changed: kept as they are.
        if (last == null || ThreadLocalRandom.current().nextInt(last.reused ? KEEP_ONE_IN : KEEP_RARELY) == 0) {
            this.last.set(new Last(values, inline, rendered));
        }
        return rendered;
    }

    /**
     * The values and inline texts of the last render, and what it gave. Shared by the threads that render: immutable,
     * and any one of them is right to keep.
     */
    private static final class Last {
        final @Nullable Object @NotNull [] values;
        final @NotNull String @Nullable [] inline;
        final @NotNull Component component;
        /**
         * Whether a render gave it again. Not volatile: a thread that sees it late only keeps a render less often.
         */
        boolean reused;

        Last(@Nullable Object @NotNull [] values, @NotNull String @Nullable [] inline, @NotNull Component component) {
            this.values = values;
            this.inline = inline;
            this.component = component;
        }

        /**
         * Whether a render with these values gives the same component: the same text, the same component instance
         * (a component is compared by identity: equals would walk it, and a new one is rarely equal).
         */
        static boolean same(@Nullable Object kept, @Nullable Object value) {
            return kept == value || kept instanceof String text && text.equals(value);
        }
    }

    /**
     * A slot's value as given, null when it had none (its value is then the placeholder as written, or null), read
     * from this render's values, or from the last render's while they are the same.
     */
    private @Nullable Object given(@Nullable Object @Nullable [] values, @Nullable Object @Nullable [] kept, int slot) {
        Object value = values != null ? values[slot] : kept[slot];
        return value == this.slotPlans[slot].written ? null : value;
    }

    private @NotNull Component build(@Nullable Object @NotNull [] values, @Nullable Set<Integer> unknown, @NotNull String @Nullable [] inline) {
        Set<Integer> unclaimed = unknown == null ? Set.of() : Set.copyOf(unknown);
        Engine.Form form;
        if (inline == null) {
            form = unclaimed.isEmpty() ? this.base : this.variant(unclaimed);
        } else {
            if (this.inline != null && unclaimed.isEmpty()) {
                Component rendered = this.engine.renderInline(this.inline, values, inline);
                if (rendered != null) {
                    return rendered;
                }
            }
            form = this.engine.inlineForm(Engine.join(this.pieces, inline), this.slots, unclaimed);
        }
        return this.engine.render(form, values);
    }

    private @NotNull Engine.Form variant(@NotNull Set<Integer> unclaimed) {
        Engine.Form form = this.variants.get(unclaimed);
        if (form != null) {
            return form;
        }
        form = this.engine.compileForm(this.compiled, this.slots, unclaimed, false);
        if (this.variants.size() < this.slots.variantLimit()) {
            this.variants.putIfAbsent(unclaimed, form);
        }
        return form;
    }

    /**
     * How the message renders when every value is known and nothing is in a tag argument (for tests).
     */
    @Nullable Engine.Form.Native inlineForm() {
        return this.inline;
    }

    @NotNull Engine.Form baseForm() {
        return this.base;
    }

    @Override
    public String toString() {
        return "Template[" + this.source + "]";
    }
}
