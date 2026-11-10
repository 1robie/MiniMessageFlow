package fr.robie.minimessageflow.testkit;

import fr.robie.minimessageflow.Placeholder;
import fr.robie.minimessageflow.PlaceholderModifiers;
import fr.robie.minimessageflow.PlaceholderValue;
import fr.robie.minimessageflow.internal.PlaceholderScanner;
import net.kyori.adventure.pointer.Pointered;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.minimessage.tag.standard.StandardTags;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;

/**
 * MessageFlow 1.1's formatting, done the slow and obvious way: every call goes through
 * {@code MiniMessage.deserialize}, nothing is cached. Engines are compared with it.
 * <ol>
 *     <li>each local placeholder ({@code %key%}, with modifiers) becomes a MiniMessage placeholder tag holding its
 *     value as a component, so a value is never parsed; inside a tag it is written as text, without the characters
 *     that could end the tag or be read again ({@code < > ' " \ % & §});</li>
 *     <li>global placeholders are then replaced by their text, which is parsed with the message, and the marker
 *     noncharacters {@code U+FDD0}/{@code U+FDD1} are removed from it;</li>
 *     <li>legacy colours become MiniMessage tags ({@link LegacyColors});</li>
 *     <li>MiniMessage parses the result with the standard tags.</li>
 * </ol>
 * From {@code TemplateCacheTest.OldFormat} and {@code AdventureMessageFormatter} of MessageFlow 1.1.
 */
public final class ReferenceFormatter implements TestFormatter {

    private static final MiniMessage MINI_MESSAGE = MiniMessage.builder()
            .tags(TagResolver.builder().resolver(StandardTags.defaults()).build())
            .build();
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();
    private static final String TAG_PREFIX = "mfph_";

    private final Placeholder globals;

    /**
     * @param globals placeholders resolved after the local ones, as text parsed with the message (MessageFlow's
     *                global placeholders)
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
            Component component = local(key, placeholders, viewer);
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
        text = stripMarkers(this.globals.parse(text, viewer));
        return MINI_MESSAGE.deserialize(LegacyColors.toMiniMessage(text), tags.build());
    }

    /**
     * The value of a local placeholder, or of a local placeholder with modifiers ({@code %price.comma%}).
     */
    private static @Nullable Component local(@NotNull String key, @NotNull Placeholder placeholders, @Nullable Pointered viewer) {
        PlaceholderValue value = placeholders.get(key);
        if (value != null) {
            return toComponent(key, value, viewer);
        }
        if (key.indexOf('.') < 0) {
            return null;
        }
        String text = PlaceholderModifiers.resolve(key, k -> {
            PlaceholderValue base = placeholders.get(k);
            return base == null ? null : PLAIN.serialize(toComponent(k, base, viewer));
        });
        return text != null ? Component.text(text) : null;
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
