package fr.robie.minimessageflow.core;

import fr.robie.minimessageflow.Placeholder;
import fr.robie.minimessageflow.TextTemplate;
import fr.robie.minimessageflow.Values;
import fr.robie.minimessageflow.internal.PlaceholderScanner;
import net.kyori.adventure.pointer.Pointered;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * A {@link TextTemplate} compiled by {@link Engine}: the text cut at its placeholders once, each placeholder's plan
 * found once. A render appends the pieces and the values to one builder sized when compiled.
 */
@ApiStatus.Internal
final class CompiledText implements TextTemplate {

    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    private final Engine engine;
    private final String source;
    /**
     * The text between placeholders: one more than {@link #plans}.
     */
    private final String[] literals;
    private final Engine.KeyPlan[] plans;
    /**
     * For each placeholder, the first one with the same key (itself when it is the first): a key is asked once per
     * render. Null when no key comes twice.
     */
    private final int @Nullable [] sources;
    private final @Nullable String fixed;
    private final List<String> placeholders;
    private final int capacity;

    CompiledText(@NotNull Engine engine, @NotNull String source) {
        this.engine = engine;
        this.source = source;
        List<String> keys = new ArrayList<>(2);
        List<int[]> ranges = new ArrayList<>(2);
        PlaceholderScanner.replace(source, (key, start) -> {
            if (!Engine.isKey(key)) {
                return null;
            }
            keys.add(key);
            ranges.add(new int[]{start, start + key.length() + 2});
            return "";
        });
        this.literals = new String[keys.size() + 1];
        this.plans = new Engine.KeyPlan[keys.size()];
        int from = 0;
        int[] sources = null;
        for (int i = 0; i < keys.size(); i++) {
            int[] range = ranges.get(i);
            this.literals[i] = source.substring(from, range[0]);
            from = range[1];
            this.plans[i] = engine.textPlan(keys.get(i));
            int first = keys.indexOf(keys.get(i));
            if (first != i) {
                if (sources == null) {
                    sources = new int[keys.size()];
                    for (int j = 0; j < sources.length; j++) {
                        sources[j] = j;
                    }
                }
                sources[i] = first;
            }
        }
        this.literals[keys.size()] = source.substring(from);
        this.sources = sources;
        this.placeholders = List.copyOf(new LinkedHashSet<>(keys));
        boolean constant = true;
        for (Engine.KeyPlan plan : this.plans) {
            constant &= plan.constant != null;
        }
        this.capacity = source.length() + 16 * this.plans.length;
        this.fixed = this.plans.length == 0 ? source : constant ? this.render(Placeholder.empty(), null, null) : null;
    }

    @Override
    public @NotNull String source() {
        return this.source;
    }

    @Override
    public @NotNull List<String> placeholders() {
        return this.placeholders;
    }

    @Override
    public boolean isStatic() {
        return this.fixed != null;
    }

    @Override
    public @NotNull String render(@NotNull Placeholder placeholders, @Nullable Pointered viewer) {
        return this.fixed != null ? this.fixed : this.render(placeholders, null, viewer);
    }

    @Override
    public @NotNull String render(@NotNull Values values, @Nullable Pointered viewer) {
        if (values.keys() != this.engine.keys()) {
            throw new IllegalArgumentException("these values were made by another MiniMessageFlow");
        }
        return this.fixed != null ? this.fixed : this.render(Placeholder.empty(), values, viewer);
    }

    private @NotNull String render(@NotNull Placeholder placeholders, @Nullable Values values, @Nullable Pointered viewer) {
        StringBuilder out = new StringBuilder(this.capacity);
        String[] asked = this.sources == null ? null : new String[this.plans.length];
        for (int i = 0; i < this.plans.length; i++) {
            out.append(this.literals[i]);
            String text;
            if (asked != null && this.sources[i] != i) {
                text = asked[this.sources[i]];
            } else {
                Object value = this.engine.value(this.plans[i], placeholders, values, viewer);
                text = value == null ? this.plans[i].written
                        : value instanceof String string ? string : PLAIN.serialize((Component) value);
                if (asked != null) {
                    asked[i] = text;
                }
            }
            out.append(text);
        }
        return out.append(this.literals[this.plans.length]).toString();
    }

    @Override
    public String toString() {
        return "TextTemplate[" + this.source + "]";
    }
}
