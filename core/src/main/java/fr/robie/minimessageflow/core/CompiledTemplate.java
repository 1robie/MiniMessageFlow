package fr.robie.minimessageflow.core;

import fr.robie.minimessageflow.Placeholder;
import fr.robie.minimessageflow.Template;
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
    private final Engine.Form base;
    private final boolean isStatic;
    private final ConcurrentHashMap<Set<Integer>, Engine.Form> variants = new ConcurrentHashMap<>();

    CompiledTemplate(@NotNull Engine engine, @NotNull String source, @NotNull String compiled, @NotNull Engine.Slots slots,
                     @Nullable List<Object> pieces, @NotNull List<String> inlineKeys, @NotNull Engine.Form base) {
        this.engine = engine;
        this.source = source;
        this.compiled = compiled;
        this.slots = slots;
        this.pieces = pieces;
        this.inlineKeys = inlineKeys;
        this.base = base;
        this.isStatic = pieces == null && slots.size() == 0 && base instanceof Engine.Form.Static;
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
    public @NotNull Component render(@NotNull Placeholder placeholders, @Nullable Pointered viewer) {
        if (this.isStatic) {
            return ((Engine.Form.Static) this.base).component();
        }
        int size = this.slots.size();
        Component[] values = new Component[size];
        Set<Integer> unknown = null;
        for (int slot = 0; slot < size; slot++) {
            String key = this.slots.key(slot);
            Component value = this.engine.value(key, placeholders, viewer);
            if (value == null) {
                if (this.slots.isName(slot)) {
                    if (unknown == null) {
                        unknown = new HashSet<>(4);
                    }
                    unknown.add(slot);
                } else {
                    value = Component.text("%" + key + "%");
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
                String key = this.inlineKeys.get(i);
                inline[i] = Engine.inlineText(key, this.engine.value(key, placeholders, viewer));
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
    @NotNull Engine.Form baseForm() {
        return this.base;
    }

    @Override
    public String toString() {
        return "Template[" + this.source + "]";
    }
}
