package fr.robie.minimessageflow.testkit;

import fr.robie.minimessageflow.Placeholder;
import fr.robie.minimessageflow.PlaceholderModifiers;
import fr.robie.minimessageflow.PlaceholderValue;
import fr.robie.minimessageflow.internal.PlaceholderScanner;
import net.kyori.adventure.pointer.Pointered;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.Context;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.ArgumentQueue;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.minimessage.tag.standard.StandardTags;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * How formatting reads, done the slow and obvious way: every call goes through {@code MiniMessage.deserialize},
 * nothing is cached. Engines are compared with it.
 * <ol>
 *     <li>each {@code %key%} (with modifiers) that has a value becomes a MiniMessage placeholder tag holding the value
 *     as a component, so a value is never parsed with the message; inside a tag it is written as plain text, without
 *     the characters that could end the tag or be read again ({@code < > ' " \ % & §});</li>
 *     <li>a value is the call's, else a global's: global text is formatted, parsed on its own (its colours and
 *     unclosed tags end with it), with the marker noncharacters {@code U+FDD0}/{@code U+FDD1} removed;</li>
 *     <li>a {@code <key>} tag that is not a standard tag and has a value is that value ({@code <key:modifier:arg>}
 *     for modifiers); without a value it is whatever MiniMessage makes of an unknown tag;</li>
 *     <li>legacy colours become MiniMessage tags ({@link LegacyColors}), then MiniMessage parses with the standard
 *     tags.</li>
 * </ol>
 * MessageFlow 1.1 ({@code TemplateCacheTest.OldFormat}) with the two deliberate changes of MiniMessageFlow:
 * global text is isolated (MessageFlow pasted it into the message), and {@code <key>} placeholders.
 */
public final class ReferenceFormatter implements TestFormatter {

    private static final TagResolver STANDARD = StandardTags.defaults();
    private static final MiniMessage MINI_MESSAGE = MiniMessage.builder()
            .tags(TagResolver.builder().resolver(STANDARD).build())
            .build();
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();
    // Not mfph_ as in MessageFlow 1.1: a message that writes <mfph_3> itself would get a value there.
    private static final String TAG_PREFIX = "mmfref_";

    private final Placeholder globals;

    /**
     * @param globals placeholders resolved after the call's ones, as formatted text (MessageFlow's global
     *                placeholders)
     */
    public ReferenceFormatter(@NotNull Placeholder globals) {
        this.globals = globals;
    }

    @Override
    public @NotNull Component format(@NotNull String message, @NotNull Placeholder placeholders, @Nullable Pointered viewer) {
        TagResolver.Builder tags = TagResolver.builder();
        Map<String, String> tagNames = new HashMap<>();
        int[] state = {0, 0}; // tag depth, scanned up to
        String text = PlaceholderScanner.replace(message, (key, start) -> {
            state[0] = tagDepth(message, state[1], start, state[0]);
            state[1] = start;
            Component component = this.value(key, placeholders, viewer);
            if (component == null) {
                return null;
            }
            if (state[0] > 0) {
                return stripTagBreaking(PLAIN.serialize(component));
            }
            String tagName = tagNames.get(key);
            if (tagName == null) {
                tagName = TAG_PREFIX + tagNames.size();
                tagNames.put(key, tagName);
                tags.resolver(net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.component(tagName, component));
            }
            return "<" + tagName + ">";
        });
        Map<String, String> ownTags = Map.copyOf(tagNames);
        TagResolver keyTags = new TagResolver() {
            @Override
            public @Nullable Tag resolve(@NotNull String name, @NotNull ArgumentQueue arguments, @NotNull Context ctx) {
                if (STANDARD.has(name) || ownTags.containsValue(name)) {
                    return null;
                }
                Component value = ReferenceFormatter.this.value(key(name, arguments), placeholders, viewer);
                return value == null ? null : Tag.selfClosingInserting(value);
            }

            @Override
            public boolean has(@NotNull String name) {
                return !STANDARD.has(name) && !ownTags.containsValue(name);
            }
        };
        tags.resolver(keyTags);
        return MINI_MESSAGE.deserialize(LegacyColors.toMiniMessage(stripMarkers(text)), tags.build());
    }

    /**
     * The value of a key: the call's value (also with modifiers), else the global's formatted text, parsed on its own.
     */
    private @Nullable Component value(@NotNull String key, @NotNull Placeholder placeholders, @Nullable Pointered viewer) {
        PlaceholderValue value = placeholders.get(key);
        if (value != null) {
            return toComponent(key, value, viewer);
        }
        if (key.indexOf('.') > 0) {
            String text = PlaceholderModifiers.resolve(key, k -> {
                PlaceholderValue base = placeholders.get(k);
                return base == null ? null : PLAIN.serialize(toComponent(k, base, viewer));
            });
            if (text != null) {
                return Component.text(text);
            }
        }
        String global = PlaceholderModifiers.resolve(key, k -> {
            PlaceholderValue base = this.globals.get(k);
            if (base == null) {
                return null;
            }
            if (base instanceof PlaceholderValue.ComponentValue(Component component)) {
                return MiniMessage.miniMessage().serialize(component);
            }
            try {
                return base.evaluate(viewer);
            } catch (Exception e) {
                return null;
            }
        });
        return global == null ? null : MINI_MESSAGE.deserialize(LegacyColors.toMiniMessage(stripMarkers(global)));
    }

    /**
     * {@code <n:plural:item:items>} reads {@code n.plural:item:items}: an argument that is a modifier's name starts a
     * modifier, any other belongs to the one before it.
     */
    private static @NotNull String key(@NotNull String name, @NotNull ArgumentQueue arguments) {
        StringBuilder key = new StringBuilder(name);
        while (arguments.hasNext()) {
            String argument = arguments.pop().value();
            boolean modifier = !argument.isEmpty() && argument.equals(argument.toLowerCase(Locale.ROOT))
                    && PlaceholderModifiers.get(argument) != null;
            key.append(modifier ? '.' : ':').append(argument);
        }
        return key.toString();
    }

    private static @NotNull Component toComponent(@NotNull String key, @NotNull PlaceholderValue value, @Nullable Pointered viewer) {
        if (value instanceof PlaceholderValue.ComponentValue(Component component)) {
            return component;
        }
        try {
            return Component.text(value.evaluate(viewer));
        } catch (Exception e) {
            return Component.text("%" + key + "%");
        }
    }

    private static int tagDepth(@NotNull String message, int from, int to, int depth) {
        for (int i = from; i < to; i++) {
            char c = message.charAt(i);
            if (c == '\\') {
                i++;
            } else if (c == '<') {
                depth++;
            } else if (c == '>' && depth > 0) {
                depth--;
            }
        }
        return depth;
    }

    private static @NotNull String stripTagBreaking(@NotNull String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c != '<' && c != '>' && c != '\'' && c != '"' && c != '\\' && c != '%' && c != '&' && c != '§') {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static @NotNull String stripMarkers(@NotNull String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c != '﷐' && c != '﷑') {
                out.append(c);
            }
        }
        return out.toString();
    }
}
