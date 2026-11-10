package fr.robie.minimessageflow.core;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.Style;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Compiles a message without MiniMessage, when every tag in it is one of the native set: colours ({@code <red>},
 * {@code <#ff00aa>}, {@code <color:...>}), decorations and their negations, {@code <reset>}, {@code <newline>},
 * {@code <click>}, {@code <hover:show_text>}, {@code <insert>}, and placeholders. Any other tag (gradient, font,
 * translatable, a user tag...) and the message is left to MiniMessage: {@link #compile} returns null.
 * <p>
 * The result is a list of {@link Part}s: fixed components built once, values with the style they inherit, and groups
 * for a click, hover or insertion around values (kept on the parent, not copied onto each leaf). Colours and
 * decorations around values are flattened onto them. Rendering builds one component from the parts: no parse, no
 * {@code compact()}.
 */
@ApiStatus.Internal
final class NativeCompiler {

    private static final Map<String, TextDecoration> DECORATIONS = Map.ofEntries(
            Map.entry("bold", TextDecoration.BOLD), Map.entry("b", TextDecoration.BOLD),
            Map.entry("italic", TextDecoration.ITALIC), Map.entry("em", TextDecoration.ITALIC), Map.entry("i", TextDecoration.ITALIC),
            Map.entry("underlined", TextDecoration.UNDERLINED), Map.entry("u", TextDecoration.UNDERLINED),
            Map.entry("strikethrough", TextDecoration.STRIKETHROUGH), Map.entry("st", TextDecoration.STRIKETHROUGH),
            Map.entry("obfuscated", TextDecoration.OBFUSCATED), Map.entry("obf", TextDecoration.OBFUSCATED));

    private static final Set<String> COLOR_TAGS = Set.of("color", "colour", "c");

    private final TagResolver standard;
    private final @Nullable TagResolver userTags;
    private final Engine.Slots slots;
    private final Set<Integer> unclaimed;

    private NativeCompiler(@NotNull TagResolver standard, @Nullable TagResolver userTags, @NotNull Engine.Slots slots,
                           @NotNull Set<Integer> unclaimed) {
        this.standard = standard;
        this.userTags = userTags;
        this.slots = slots;
        this.unclaimed = unclaimed;
    }

    /**
     * @param text the message with slot tags, legacy codes already converted
     * @return the parts, or null when the message needs MiniMessage
     */
    static @Nullable List<Part> compile(@NotNull String text, @NotNull TagResolver standard, @Nullable TagResolver userTags,
                                        @NotNull Engine.Slots slots, @NotNull Set<Integer> unclaimed) {
        try {
            NativeCompiler compiler = new NativeCompiler(standard, userTags, slots, unclaimed);
            Element root = compiler.parse(text, true);
            return merge(reduce(root.children, Style.empty()));
        } catch (Unsupported e) {
            return null;
        }
    }

    // --- parts and rendering ------------------------------------------------------------------------------------

    sealed interface Part {
        /**
         * Built once.
         */
        record Fixed(@NotNull Component component) implements Part {
        }

        /**
         * A slot's value, with the colours and decorations around it.
         */
        record Value(int slot, @NotNull Style style) implements Part {
        }

        /**
         * A click, hover or insertion (with the style around it) over parts that hold values.
         */
        record Group(@NotNull Style style, @NotNull List<Part> parts) implements Part {
        }
    }

    static @NotNull Component render(@NotNull List<Part> parts, @Nullable Component @NotNull [] values) {
        return Component.empty().children(renderParts(parts, values));
    }

    private static @NotNull List<Component> renderParts(@NotNull List<Part> parts, @Nullable Component @NotNull [] values) {
        List<Component> out = new ArrayList<>(parts.size());
        for (Part part : parts) {
            switch (part) {
                case Part.Fixed(Component component) -> out.add(component);
                case Part.Value(int slot, Style style) -> {
                    Component value = values[slot];
                    if (value != null) {
                        out.add(styled(value, style));
                    }
                }
                case Part.Group(Style style, List<Part> children) ->
                        out.add(Component.text("", style).children(renderParts(children, values)));
            }
        }
        return out;
    }

    /**
     * A value placed under the style around it: its own style wins, the rest comes from around it, as when
     * MiniMessage inserts it as a child.
     */
    private static @NotNull Component styled(@NotNull Component value, @NotNull Style style) {
        if (style.isEmpty()) {
            return value;
        }
        if (value instanceof TextComponent text && value.style().isEmpty() && value.children().isEmpty()) {
            return Component.text(text.content(), style);
        }
        return value.style(value.style().merge(style, Style.Merge.Strategy.IF_ABSENT_ON_TARGET));
    }

    // --- passes -------------------------------------------------------------------------------------------------

    /**
     * Folds what has no value into fixed components, flattens colours and decorations onto values, keeps events on
     * a group.
     */
    private static @NotNull List<Part> reduce(@NotNull List<Node> nodes, @NotNull Style inherited) {
        List<Part> parts = new ArrayList<>(nodes.size());
        for (Node node : nodes) {
            switch (node) {
                case Node.Text(String text) -> parts.add(new Part.Fixed(Component.text(text, inherited)));
                case Node.Slot(int slot) -> parts.add(new Part.Value(slot, inherited));
                case Element element -> {
                    Style style = element.style.merge(inherited, Style.Merge.Strategy.IF_ABSENT_ON_TARGET);
                    if (!element.hasSlot()) {
                        if (element.hasText()) { // <bold><hover:...><reset> shows nothing: no part
                            parts.add(new Part.Fixed(element.build().style(style)));
                        }
                    } else if (hasEvents(element.style)) {
                        parts.add(new Part.Group(style, merge(reduce(element.children, Style.empty()))));
                    } else {
                        parts.addAll(reduce(element.children, style));
                    }
                }
            }
        }
        return parts;
    }

    private static boolean hasEvents(@NotNull Style style) {
        return style.clickEvent() != null || style.hoverEvent() != null || style.insertion() != null;
    }

    /**
     * Joins neighbouring fixed parts into one component.
     */
    private static @NotNull List<Part> merge(@NotNull List<Part> parts) {
        List<Part> merged = new ArrayList<>(parts.size());
        List<Component> run = new ArrayList<>();
        for (Part part : parts) {
            if (part instanceof Part.Fixed(Component component)) {
                run.add(component);
                continue;
            }
            flush(run, merged);
            merged.add(part);
        }
        flush(run, merged);
        return List.copyOf(merged);
    }

    private static void flush(@NotNull List<Component> run, @NotNull List<Part> merged) {
        if (run.isEmpty()) {
            return;
        }
        merged.add(new Part.Fixed(run.size() == 1 ? run.getFirst() : Component.empty().children(run)));
        run.clear();
    }

    // --- parsing ------------------------------------------------------------------------------------------------

    private sealed interface Node permits Node.Text, Node.Slot, Element {
        record Text(@NotNull String text) implements Node {
        }

        record Slot(int slot) implements Node {
        }
    }

    /**
     * A tag that wraps what follows it until it is closed.
     */
    private static final class Element implements Node {
        final String name;
        final Style style;
        final List<Node> children = new ArrayList<>();

        Element(@NotNull String name, @NotNull Style style) {
            this.name = name;
            this.style = style;
        }

        boolean hasSlot() {
            for (Node child : this.children) {
                if (child instanceof Node.Slot || (child instanceof Element element && element.hasSlot())) {
                    return true;
                }
            }
            return false;
        }

        boolean hasText() {
            for (Node child : this.children) {
                if (child instanceof Node.Text(String text) && !text.isEmpty() || child instanceof Element element && element.hasText()) {
                    return true;
                }
            }
            return false;
        }

        @NotNull Component build() {
            List<Component> built = new ArrayList<>(this.children.size());
            for (Node child : this.children) {
                switch (child) {
                    case Node.Text(String text) -> built.add(Component.text(text));
                    case Element element -> built.add(element.build());
                    case Node.Slot slot -> throw new IllegalStateException("slot in a fixed part");
                }
            }
            return Component.text("", this.style).children(built);
        }
    }

    /**
     * The message needs MiniMessage.
     */
    private static final class Unsupported extends RuntimeException {
        private static final Unsupported INSTANCE = new Unsupported();

        private Unsupported() {
            super(null, null, false, false);
        }
    }

    private static Unsupported unsupported() {
        return Unsupported.INSTANCE;
    }

    /**
     * A tag as written: {@code <name:arg:arg>} or {@code </name>}, from {@code start} to {@code end} (exclusive).
     */
    private record Tag(@NotNull String name, @NotNull List<String> arguments, boolean closing, int start, int end) {
    }

    private @NotNull Element parse(@NotNull String text, boolean slotsAllowed) {
        Element root = new Element("", Style.empty());
        Deque<Element> open = new ArrayDeque<>();
        open.push(root);
        StringBuilder pending = new StringBuilder();
        int length = text.length();
        int i = 0;
        while (i < length) {
            char c = text.charAt(i);
            if (c == '\\' && i + 1 < length && (text.charAt(i + 1) == '<' || text.charAt(i + 1) == '\\')) {
                pending.append(text.charAt(i + 1));
                i += 2;
                continue;
            }
            if (c == '<') {
                Tag tag = readTag(text, i);
                if (tag != null) {
                    flush(pending, open.peek());
                    this.handle(tag, text, open, pending, slotsAllowed);
                    i = tag.end();
                    continue;
                }
                // Text that is not a tag. "<>" and "</>" are text in MiniMessage; for anything else before a later
                // '>' ("<3 <red>", "a < b > c", "<\\<u>") MiniMessage's tokenizer has rules not worth copying.
                if (text.startsWith("<>", i) || text.startsWith("</>", i)) {
                    int end = text.indexOf('>', i) + 1;
                    pending.append(text, i, end);
                    i = end;
                    continue;
                }
                if (text.indexOf('>', i) >= 0) {
                    throw unsupported();
                }
            }
            pending.append(c);
            i++;
        }
        flush(pending, open.peek());
        return root;
    }

    private static void flush(@NotNull StringBuilder pending, @NotNull Element current) {
        if (!pending.isEmpty()) {
            current.children.add(new Node.Text(pending.toString()));
            pending.setLength(0);
        }
    }

    private void handle(@NotNull Tag tag, @NotNull String text, @NotNull Deque<Element> open, @NotNull StringBuilder pending,
                        boolean slotsAllowed) {
        String name = tag.name();
        Element current = open.peek();
        if (tag.closing()) {
            for (Element element : open) {
                if (element.name.equals(name) && element != open.peekLast()) {
                    while (open.peek() != element) {
                        open.pop();
                    }
                    open.pop();
                    return;
                }
            }
            // A closing tag matches its opening tag by name only (</b> does not close <bold>); one that matches no open
            // tag is text, as in MiniMessage.
            pending.append(text, tag.start(), tag.end());
            return;
        }
        int slotTag = Engine.slotTagIndex(name);
        if (slotTag >= 0) {
            if (!slotsAllowed || !tag.arguments().isEmpty() || slotTag >= this.slots.size()) {
                throw unsupported();
            }
            current.children.add(new Node.Slot(slotTag));
            return;
        }
        if (this.userTags != null && this.userTags.has(name)) {
            throw unsupported();
        }
        Style style = this.nativeStyle(name, tag.arguments());
        if (style != null) {
            Element element = new Element(name, style);
            current.children.add(element);
            open.push(element);
            return;
        }
        switch (name) {
            case "reset" -> {
                expectNoArguments(tag);
                while (open.size() > 1) {
                    open.pop();
                }
            }
            case "newline", "br" -> {
                expectNoArguments(tag);
                pending.append('\n');
            }
            default -> {
                if (this.standard.has(name)) {
                    throw unsupported();
                }
                Integer slot = this.slots.name(TagKeys.key(name, tag.arguments()), false);
                if (slot == null || this.unclaimed.contains(slot)) {
                    pending.append(text, tag.start(), tag.end()); // an unknown tag: text, as MiniMessage shows it
                } else if (!slotsAllowed) {
                    throw unsupported();
                } else {
                    current.children.add(new Node.Slot(slot));
                }
            }
        }
    }

    private boolean isTag(@NotNull String name) {
        return Engine.slotTagIndex(name) >= 0 || this.standard.has(name)
                || (this.userTags != null && this.userTags.has(name));
    }

    private static void expectNoArguments(@NotNull Tag tag) {
        if (!tag.arguments().isEmpty()) {
            throw unsupported();
        }
    }

    /**
     * The style a native styling tag gives, or null when the tag is not one.
     */
    private @Nullable Style nativeStyle(@NotNull String name, @NotNull List<String> arguments) {
        TextDecoration decoration = DECORATIONS.get(name);
        if (decoration != null) {
            if (arguments.isEmpty()) {
                return Style.style(decoration);
            }
            if (arguments.size() == 1 && arguments.getFirst().equals("false")) {
                return Style.empty().decoration(decoration, false);
            }
            if (arguments.size() == 1 && arguments.getFirst().equals("true")) {
                return Style.style(decoration);
            }
            throw unsupported();
        }
        if (name.startsWith("!")) {
            TextDecoration negated = DECORATIONS.get(name.substring(1));
            if (negated == null || !arguments.isEmpty()) {
                throw unsupported();
            }
            return Style.empty().decoration(negated, false);
        }
        TextColor color = color(name);
        if (color != null) {
            if (!arguments.isEmpty()) {
                throw unsupported();
            }
            return Style.style(color);
        }
        if (COLOR_TAGS.contains(name)) {
            TextColor argument = arguments.size() == 1 ? color(arguments.getFirst()) : null;
            if (argument == null) {
                throw unsupported();
            }
            return Style.style(argument);
        }
        return switch (name) {
            case "click" -> click(arguments);
            case "hover" -> {
                if (arguments.size() != 2 || !arguments.getFirst().equals("show_text")) {
                    throw unsupported();
                }
                Element hover = this.parse(arguments.get(1), false);
                yield Style.style().hoverEvent(HoverEvent.showText(hover.build())).build();
            }
            case "insert" -> {
                if (arguments.size() != 1) {
                    throw unsupported();
                }
                yield Style.style().insertion(arguments.getFirst()).build();
            }
            default -> null;
        };
    }

    private static @Nullable TextColor color(@NotNull String name) {
        if (name.startsWith("#")) {
            return name.length() == 7 ? TextColor.fromHexString(name) : null;
        }
        return switch (name) {
            case "grey" -> NamedTextColor.GRAY;
            case "dark_grey" -> NamedTextColor.DARK_GRAY;
            default -> NamedTextColor.NAMES.value(name);
        };
    }

    /**
     * The style of a click. {@code ClickEvent} is never named as a type: it is generic from Adventure 5.
     */
    private static @NotNull Style click(@NotNull List<String> arguments) {
        if (arguments.size() != 2) {
            throw unsupported();
        }
        String value = arguments.get(1);
        Style.Builder style = Style.style();
        switch (arguments.getFirst()) {
            case "run_command" -> style.clickEvent(ClickEvent.runCommand(value));
            case "suggest_command" -> style.clickEvent(ClickEvent.suggestCommand(value));
            case "open_url" -> style.clickEvent(ClickEvent.openUrl(value));
            case "copy_to_clipboard" -> style.clickEvent(ClickEvent.copyToClipboard(value));
            default -> throw unsupported();
        }
        return style.build();
    }

    /**
     * Reads a tag at {@code start} ({@code <}), or null when the text there is not a tag (it is then text).
     */
    private static @Nullable Tag readTag(@NotNull String text, int start) {
        int length = text.length();
        int i = start + 1;
        boolean closing = i < length && text.charAt(i) == '/';
        if (closing) {
            i++;
        }
        int nameStart = i;
        if (i < length && (text.charAt(i) == '!' || text.charAt(i) == '#' || text.charAt(i) == '?')) {
            i++;
        }
        while (i < length && isNameChar(text.charAt(i))) {
            i++;
        }
        if (i == nameStart || i >= length) {
            return null;
        }
        String name = text.substring(nameStart, i).toLowerCase(Locale.ROOT);
        List<String> arguments = new ArrayList<>(2);
        while (i < length && text.charAt(i) == ':') {
            i++;
            if (i < length && (text.charAt(i) == '\'' || text.charAt(i) == '"')) {
                char quote = text.charAt(i++);
                StringBuilder argument = new StringBuilder();
                boolean closed = false;
                while (i < length) {
                    char c = text.charAt(i);
                    if (c == '\\' && i + 1 < length && (text.charAt(i + 1) == quote || text.charAt(i + 1) == '\\')) {
                        argument.append(text.charAt(i + 1));
                        i += 2;
                        continue;
                    }
                    i++;
                    if (c == quote) {
                        closed = true;
                        break;
                    }
                    argument.append(c);
                }
                if (!closed) {
                    return null;
                }
                arguments.add(argument.toString());
            } else {
                int argumentStart = i;
                while (i < length && text.charAt(i) != ':' && text.charAt(i) != '>') {
                    char c = text.charAt(i);
                    if (c == '<' || c == '\'' || c == '"' || c == '\\') {
                        throw unsupported();
                    }
                    i++;
                }
                arguments.add(text.substring(argumentStart, i));
            }
        }
        if (i >= length || text.charAt(i) != '>') {
            return null;
        }
        if (closing && !arguments.isEmpty()) {
            throw unsupported();
        }
        return new Tag(name, List.copyOf(arguments), closing, start, i + 1);
    }

    private static boolean isNameChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_' || c == '-';
    }
}
