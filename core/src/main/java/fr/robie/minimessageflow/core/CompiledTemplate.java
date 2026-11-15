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
    private final boolean repeatedSlots;
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
        boolean repeated = false;
        for (int slot = 0; slot < this.slotSources.length; slot++) {
            this.slotSources[slot] = firstSlot(slots, slots.key(slot));
            this.slotPlans[slot] = engine.plan(slots.key(slot));
            repeated |= this.slotSources[slot] != slot;
        }
        this.repeatedSlots = repeated;
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
        // Each a String (plain text), a Component, or null.
        Object[] values = new Object[size];
        // The values as given (null when there is none), for the other places the same key is written.
        Object[] given = this.pieces != null || this.repeatedSlots ? new Object[size] : null;
        Set<Integer> unknown = null;
        for (int slot = 0; slot < size; slot++) {
            int source = this.slotSources[slot];
            Object value = given != null && source != slot ? given[source] : this.engine.value(this.slotPlans[slot], placeholders, fast, viewer);
            if (given != null) {
                given[slot] = value;
            }
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
            values[slot] = value;
        }
        Set<Integer> unclaimed = unknown == null ? Set.of() : Set.copyOf(unknown);

        Engine.Form form;
        if (this.pieces == null) {
            form = unclaimed.isEmpty() ? this.base : this.variant(unclaimed);
        } else {
            String[] inline = new String[this.inlineKeys.size()];
            for (int i = 0; i < inline.length; i++) {
                int source = this.inlineSources[i];
                if (source < 0 && -1 - source < i) {
                    inline[i] = inline[-1 - source];
                } else {
                    inline[i] = Engine.inlineText(this.inlinePlans[i], source >= 0 ? given[source] : this.engine.value(this.inlinePlans[i], placeholders, fast, viewer));
                }
            }
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
