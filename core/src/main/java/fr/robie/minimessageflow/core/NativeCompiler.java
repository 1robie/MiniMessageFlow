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
import net.kyori.adventure.util.HSVLike;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Compiles a message without MiniMessage, when every tag in it is one of the native set: colours ({@code <red>},
 * {@code <#ff00aa>}, {@code <color:...>}), decorations and their negations, {@code <reset>}, {@code <newline>},
 * {@code <click>}, {@code <hover:show_text>}, {@code <insert>}, {@code <gradient>}, {@code <rainbow>}, and placeholders.
 * Any other tag (font, translatable, a user tag...) and the message is left to MiniMessage: {@link #compile} returns
 * null.
 * <p>
 * The result is a list of {@link Part}s: fixed components built once, values with the style they inherit, groups for
 * a click, hover or insertion around values (kept on the parent, not copied onto each leaf), events whose argument
 * holds values (rebuilt at render), and colour changes (gradient, rainbow) over values (coloured at render).
 * Rendering builds one component from the parts: no parse, no {@code compact()}.
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
    private static final String[] NO_INLINE = new String[0];

    private final TagResolver standard;
    private final @Nullable TagResolver userTags;
    private final Engine.Slots slots;
    private final Set<Integer> unclaimed;
    private final boolean legacyCodes;
    private final boolean discover;

    private NativeCompiler(@NotNull TagResolver standard, @Nullable TagResolver userTags, @NotNull Engine.Slots slots,
                           @NotNull Set<Integer> unclaimed, boolean legacyCodes, boolean discover) {
        this.standard = standard;
        this.userTags = userTags;
        this.slots = slots;
        this.unclaimed = unclaimed;
        this.legacyCodes = legacyCodes;
        this.discover = discover;
    }

    /**
     * @param text        the message with slot tags, legacy codes already converted; it may hold inline markers
     *                    ({@link Engine#INLINE_START}) in click and insert arguments
     * @param legacyCodes whether legacy codes are read (inline values are converted at render, as the stage 1 engine
     *                    converts the text they are written into)
     * @return the parts, or null when the message needs MiniMessage
     */
    static @Nullable List<Part> compile(@NotNull String text, @NotNull TagResolver standard, @Nullable TagResolver userTags,
                                        @NotNull Engine.Slots slots, @NotNull Set<Integer> unclaimed, boolean legacyCodes) {
        return compile(text, standard, userTags, slots, unclaimed, legacyCodes, false);
    }

    /**
     * @param discover whether {@code <key>} tags not yet in {@code slots} are added to it (the native compile of a new
     *                 message; {@code slots} must then be a copy that is dropped when this returns null)
     */
    static @Nullable List<Part> compile(@NotNull String text, @NotNull TagResolver standard, @Nullable TagResolver userTags,
                                        @NotNull Engine.Slots slots, @NotNull Set<Integer> unclaimed, boolean legacyCodes,
                                        boolean discover) {
        try {
            NativeCompiler compiler = new NativeCompiler(standard, userTags, slots, unclaimed, legacyCodes, discover);
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
        record Group(@NotNull Style style, @NotNull List<Part> parts, @Nullable Component base) implements Part {
        }

        /**
         * A click or insertion whose argument holds values, built at render, over its parts.
         */
        record Event(@NotNull Style style, @NotNull String action, @NotNull Argument argument, @NotNull List<Part> parts) implements Part {
        }

        /**
         * A gradient or rainbow over text and values, coloured at render.
         */
        record Transform(@NotNull Style style, @NotNull ColorChange change, @NotNull List<Item> items) implements Part {
        }
    }

    /**
     * Text or a slot under a gradient, with the decorations (and events) inside the gradient around it.
     */
    record Item(@Nullable String text, int slot, @NotNull Style style) {
    }

    /**
     * An argument made of literal pieces ({@code String}) and inline values ({@link Inline}).
     */
    record Argument(@NotNull List<Object> pieces, boolean quoted, boolean legacyCodes) {
        /**
         * @return the argument, or null when a value would change how MiniMessage splits the tag (in an argument
         * without quotes, a {@code :} or a {@code <} from a legacy colour): that render goes the stage 1 way
         */
        @Nullable String render(@NotNull String @NotNull [] inline) {
            StringBuilder out = new StringBuilder(32);
            for (Object piece : this.pieces) {
                if (piece instanceof Inline(int index, char before)) {
                    String value = inline[index];
                    // The stage 1 engine converts legacy codes in the text the value is written into: a "#rrggbb" in a
                    // value becomes a colour tag there too (& and § are already gone from inline values).
                    if (this.legacyCodes && value.indexOf('#') >= 0) {
                        value = LegacyCodes.toMiniMessage(before + value).substring(1);
                    }
                    if (!this.quoted && (value.indexOf(':') >= 0 || value.indexOf('<') >= 0)) {
                        return null;
                    }
                    out.append(value);
                } else {
                    out.append((String) piece);
                }
            }
            return out.toString();
        }
    }

    /**
     * Inline value number {@code index}, written after the character {@code before}.
     */
    record Inline(int index, char before) {
    }

    /**
     * Whether a gradient or rainbow is coloured at render: its colours depend on the values' length.
     */
    static boolean hasTransform(@NotNull List<Part> parts) {
        for (Part part : parts) {
            if (part instanceof Part.Transform
                    || part instanceof Part.Group(Style style, List<Part> children, Component base) && hasTransform(children)
                    || part instanceof Part.Event event && hasTransform(event.parts())) {
                return true;
            }
        }
        return false;
    }

    static @Nullable Component render(@NotNull List<Part> parts, @Nullable Component @NotNull [] values) {
        return render(parts, values, NO_INLINE);
    }

    /**
     * @return the component, or null when a gradient meets a value it can't colour the way MiniMessage does (a value
     * with a style or children): such a render goes through MiniMessage
     */
    static @Nullable Component render(@NotNull List<Part> parts, @Nullable Component @NotNull [] values, @NotNull String @NotNull [] inline) {
        List<Component> children = renderParts(parts, values, inline);
        return children == null ? null : Component.empty().children(children);
    }

    private static @Nullable List<Component> renderParts(@NotNull List<Part> parts, @Nullable Component @NotNull [] values,
                                                         @NotNull String @NotNull [] inline) {
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
                case Part.Group(Style style, List<Part> children, Component base) -> {
                    List<Component> rendered = renderParts(children, values, inline);
                    if (rendered == null) {
                        return null;
                    }
                    out.add((base == null ? Component.text("", style) : base.style(style)).children(rendered));
                }
                case Part.Event(Style style, String action, Argument argument, List<Part> children) -> {
                    List<Component> rendered = renderParts(children, values, inline);
                    if (rendered == null) {
                        return null;
                    }
                    String value = argument.render(inline);
                    if (value == null) {
                        return null;
                    }
                    Style event;
                    try {
                        event = action.equals("insert")
                                ? Style.style().insertion(value).build()
                                : click(List.of(action, value));
                    } catch (RuntimeException e) {
                        // Adventure 5.1+ checks a URL when the event is built: MiniMessage decides what an invalid
                        // one gives, so this render is its.
                        return null;
                    }
                    out.add(Component.text("", event.merge(style, Style.Merge.Strategy.IF_ABSENT_ON_TARGET)).children(rendered));
                }
                case Part.Transform(Style style, ColorChange change, List<Item> items) -> {
                    Component colored = transform(style, change, items, values);
                    if (colored == null) {
                        return null;
                    }
                    out.add(colored);
                }
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

    // --- gradients and rainbows ---------------------------------------------------------------------------------

    /**
     * How MiniMessage colours text under {@code <gradient>} or {@code <rainbow>} ({@code GradientTag},
     * {@code RainbowTag}; the same maths from Adventure 4.17 to 5.2): one colour per code point, in order.
     */
    sealed interface ColorChange {
        @NotNull Cursor start(int size);

        record Gradient(@NotNull TextColor @NotNull [] colors, double phase) implements ColorChange {
            @Override
            public @NotNull Cursor start(int size) {
                double multiplier = size == 1 ? 0 : (double) (this.colors.length - 1) / (size - 1);
                double phase = this.phase * (this.colors.length - 1);
                return new Cursor() {
                    private int index;

                    @Override
                    public @NotNull TextColor next() {
                        double position = (this.index++ * multiplier) + phase;
                        int lowUnclamped = (int) Math.floor(position);
                        int high = (int) Math.ceil(position) % Gradient.this.colors.length;
                        int low = lowUnclamped % Gradient.this.colors.length;
                        return TextColor.lerp((float) position - lowUnclamped, Gradient.this.colors[low], Gradient.this.colors[high]);
                    }
                };
            }
        }

        record Rainbow(boolean reversed, double dividedPhase) implements ColorChange {
            @Override
            public @NotNull Cursor start(int size) {
                return new Cursor() {
                    private int index = Rainbow.this.reversed ? size - 1 : 0;

                    @Override
                    public @NotNull TextColor next() {
                        float index = this.index;
                        float hue = (float) ((index / size + Rainbow.this.dividedPhase) % 1f);
                        TextColor color = TextColor.color(HSVLike.hsvLike(hue, 1f, 1f));
                        if (Rainbow.this.reversed) {
                            this.index = this.index == 0 ? size - 1 : this.index - 1;
                        } else {
                            this.index++;
                        }
                        return color;
                    }
                };
            }
        }
    }

    interface Cursor {
        @NotNull TextColor next();
    }

    private static @Nullable Component transform(@NotNull Style style, @NotNull ColorChange change, @NotNull List<Item> items,
                                                 @Nullable Component @NotNull [] values) {
        String[] texts = new String[items.size()];
        int size = 0;
        for (int i = 0; i < texts.length; i++) {
            Item item = items.get(i);
            String text = item.text();
            if (text == null) {
                Component value = values[item.slot()];
                if (!(value instanceof TextComponent component) || !value.style().isEmpty() || !value.children().isEmpty()) {
                    return null;
                }
                text = component.content();
            }
            texts[i] = text;
            size += text.codePointCount(0, text.length());
        }
        Cursor cursor = change.start(size);
        List<Component> chars = new ArrayList<>(size);
        int[] holder = new int[1];
        for (int i = 0; i < texts.length; i++) {
            Style itemStyle = items.get(i).style();
            String text = texts[i];
            for (int offset = 0; offset < text.length(); ) {
                holder[0] = text.codePointAt(offset);
                offset += Character.charCount(holder[0]);
                chars.add(Component.text(new String(holder, 0, 1), itemStyle.color(cursor.next())));
            }
        }
        return Component.text("", style).children(chars);
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
                    if (!element.isDynamic()) {
                        if (element.hasText()) { // <bold><hover:...><reset> shows nothing: no part
                            parts.add(new Part.Fixed(element.build().style(style)));
                        }
                    } else if (element.change != null) {
                        parts.add(new Part.Transform(style, element.change, items(element.children, Style.empty())));
                    } else if (element.action != null) {
                        parts.add(new Part.Event(style, element.action, element.argument, merge(reduce(element.children, Style.empty()))));
                    } else if (hasEvents(element.style) || element.base != null) {
                        // An event stays on its parent; a keybind or translatable wraps what follows it.
                        parts.add(new Part.Group(style, merge(reduce(element.children, Style.empty())), element.base));
                    } else {
                        parts.addAll(reduce(element.children, style));
                    }
                }
            }
        }
        return parts;
    }

    /**
     * The text and slots under a gradient, each with the styles between it and the gradient. A colour, an event
     * with values or another gradient in there: MiniMessage's rules for them are not copied.
     */
    private static @NotNull List<Item> items(@NotNull List<Node> nodes, @NotNull Style inherited) {
        List<Item> items = new ArrayList<>();
        for (Node node : nodes) {
            switch (node) {
                case Node.Text(String text) -> items.add(new Item(text, -1, inherited));
                case Node.Slot(int slot) -> items.add(new Item(null, slot, inherited));
                case Element element -> {
                    if (element.change != null || element.action != null || element.base != null || element.style.color() != null) {
                        throw unsupported();
                    }
                    items.addAll(items(element.children, element.style.merge(inherited, Style.Merge.Strategy.IF_ABSENT_ON_TARGET)));
                }
            }
        }
        return items;
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
        /**
         * {@code run_command}..., or {@code insert}, when the argument holds inline values.
         */
        final @Nullable String action;
        final @Nullable Argument argument;
        final @Nullable ColorChange change;
        final List<Node> children = new ArrayList<>();

        /**
         * The component an inserting tag puts here ({@code <key>}, {@code <lang>}), that what follows it goes into;
         * null for a styling tag.
         */
        final @Nullable Component base;

        Element(@NotNull String name, @NotNull Style style) {
            this(name, style, null, null, null, null);
        }

        Element(@NotNull String name, @NotNull Component base) {
            this(name, Style.empty(), null, null, null, base);
        }

        Element(@NotNull String name, @NotNull Style style, @Nullable String action, @Nullable Argument argument,
                @Nullable ColorChange change) {
            this(name, style, action, argument, change, null);
        }

        private Element(@NotNull String name, @NotNull Style style, @Nullable String action, @Nullable Argument argument,
                        @Nullable ColorChange change, @Nullable Component base) {
            this.name = name;
            this.style = style;
            this.action = action;
            this.argument = argument;
            this.change = change;
            this.base = base;
        }

        /**
         * Whether something in it changes with the values.
         */
        boolean isDynamic() {
            if (this.action != null) {
                return true;
            }
            for (Node child : this.children) {
                if (child instanceof Node.Slot || (child instanceof Element element && element.isDynamic())) {
                    return true;
                }
            }
            return false;
        }

        boolean hasText() {
            if (this.base != null) {
                return true; // a keybind or translatable shows something
            }
            for (Node child : this.children) {
                if (child instanceof Node.Text(String text) && !text.isEmpty() || child instanceof Element element && element.hasText()) {
                    return true;
                }
            }
            return false;
        }

        @NotNull Component build() {
            if (this.change != null) {
                Component colored = transform(this.style, this.change, items(this.children, Style.empty()), new Component[0]);
                if (colored == null) {
                    throw new IllegalStateException("value in a fixed gradient");
                }
                return colored;
            }
            List<Component> built = new ArrayList<>(this.children.size());
            for (Node child : this.children) {
                switch (child) {
                    case Node.Text(String text) -> built.add(Component.text(text));
                    case Element element -> built.add(element.build());
                    case Node.Slot slot -> throw new IllegalStateException("slot in a fixed part");
                }
            }
            return (this.base == null ? Component.text("", this.style) : this.base.style(this.style)).children(built);
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
     *
     * @param before the character just before each argument in the text (its quote, or the {@code :})
     */
    private record Tag(@NotNull String name, @NotNull List<String> arguments, @NotNull List<Character> before,
                       boolean closing, int start, int end) {
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
            if (c == Engine.INLINE_START) {
                throw unsupported(); // an inline value outside a click or insert argument
            }
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
        Element element = this.element(name, tag, slotsAllowed);
        if (element != null) {
            current.children.add(element);
            open.push(element);
            return;
        }
        if (hasInline(tag.arguments())) {
            throw unsupported();
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
                // Discovering: a tag no standard or user tag claims is a <key> placeholder (as the stage 1 discovery parse
                // claims it), its slot added to the table.
                Integer slot = this.slots.name(TagKeys.key(name, tag.arguments()), this.discover);
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

    private static boolean hasInline(@NotNull List<String> arguments) {
        for (String argument : arguments) {
            if (argument.indexOf(Engine.INLINE_START) >= 0) {
                return true;
            }
        }
        return false;
    }

    private static void expectNoArguments(@NotNull Tag tag) {
        if (!tag.arguments().isEmpty()) {
            throw unsupported();
        }
    }

    /**
     * The element of a native tag that wraps what follows it, or null when the tag is not one.
     */
    private @Nullable Element element(@NotNull String name, @NotNull Tag tag, boolean slotsAllowed) {
        List<String> arguments = tag.arguments();
        // An inline value may only be in the argument of a click (its value) or an insertion.
        if ((name.equals("click") || name.equals("insert")) && hasInline(arguments)) {
            int index = name.equals("click") ? 1 : 0;
            if (!slotsAllowed || arguments.size() != index + 1 || hasInline(arguments.subList(0, index))) {
                throw unsupported();
            }
            Argument argument = this.argument(arguments.get(index), tag.before().get(index));
            if (name.equals("click") && !CLICK_ACTIONS.contains(arguments.getFirst())) {
                throw unsupported();
            }
            return new Element(name, Style.empty(), name.equals("click") ? arguments.getFirst() : "insert", argument, null);
        }
        if (hasInline(arguments)) {
            throw unsupported();
        }
        if (name.equals("gradient")) {
            return new Element(name, Style.empty(), null, null, gradient(arguments));
        }
        if (name.equals("rainbow")) {
            return new Element(name, Style.empty(), null, null, rainbow(arguments));
        }
        switch (name) {
            case "key" -> {
                if (arguments.size() != 1) {
                    throw unsupported();
                }
                return new Element(name, Component.keybind(arguments.getFirst()));
            }
            case "lang", "tr", "translate" -> {
                if (arguments.isEmpty()) {
                    throw unsupported();
                }
                return new Element(name, Component.translatable(arguments.getFirst(), this.translationArguments(arguments, 1)));
            }
            case "lang_or", "tr_or", "translate_or" -> {
                if (arguments.size() < 2) {
                    throw unsupported();
                }
                return new Element(name, Component.translatable(arguments.getFirst(), arguments.get(1), this.translationArguments(arguments, 2)));
            }
            case "font" -> {
                return new Element(name, font(arguments));
            }
            case "transition" -> {
                return new Element(name, Style.style(transition(arguments)));
            }
            case "shadow", "!shadow" -> {
                return new Element(name, shadow(name, arguments));
            }
            default -> {
                Style style = this.nativeStyle(name, arguments);
                return style == null ? null : new Element(name, style);
            }
        }
    }

    /**
     * The arguments of a translatable from {@code from} on, each read as MiniMessage, as MiniMessage does
     * ({@code ctx.deserialize}): natively here, without values.
     */
    private @NotNull List<Component> translationArguments(@NotNull List<String> arguments, int from) {
        List<Component> with = new ArrayList<>(arguments.size() - from);
        for (int i = from; i < arguments.size(); i++) {
            with.add(this.parse(arguments.get(i), false).build().compact());
        }
        return with;
    }

    /**
     * {@code <font:value>} or {@code <font:namespace:value>}.
     */
    private static @NotNull Style font(@NotNull List<String> arguments) {
        try {
            return switch (arguments.size()) {
                case 1 -> Style.style().font(net.kyori.adventure.key.Key.key(arguments.getFirst())).build();
                case 2 -> Style.style().font(net.kyori.adventure.key.Key.key(arguments.getFirst(), arguments.get(1))).build();
                default -> throw unsupported();
            };
        } catch (Unsupported e) {
            throw e;
        } catch (RuntimeException e) { // InvalidKeyException: MiniMessage reports it
            throw unsupported();
        }
    }

    /**
     * {@code <transition:colors...[:phase]>}: one colour, picked along the colours by the phase ({@code TransitionTag},
     * the same maths from 4.17 to 5.2). Only arguments every version reads the same way: lower-case colour names,
     * {@code #rrggbb}, a last numeric phase in [-1, 1] (4.17 reads the phase first, later versions the colour first;
     * 4.17 has no {@code grey} alias).
     */
    private static @NotNull TextColor transition(@NotNull List<String> arguments) {
        List<TextColor> colors = new ArrayList<>(arguments.size());
        float phase = 0;
        for (int i = 0; i < arguments.size(); i++) {
            String argument = arguments.get(i);
            TextColor color = argument.startsWith("#") ? (argument.length() == 7 ? TextColor.fromHexString(argument) : null)
                    : NamedTextColor.NAMES.value(argument);
            if (color != null) {
                colors.add(color);
                continue;
            }
            if (i != arguments.size() - 1) {
                throw unsupported();
            }
            double parsed;
            try {
                parsed = Double.parseDouble(argument);
            } catch (NumberFormatException e) {
                throw unsupported();
            }
            if (!(parsed >= -1d && parsed <= 1d)) {
                throw unsupported();
            }
            phase = (float) parsed;
        }
        if (!arguments.isEmpty() && colors.size() < 2) {
            throw unsupported();
        }
        boolean negativePhase = phase < 0;
        if (negativePhase) {
            phase = 1 + phase;
            Collections.reverse(colors);
        }
        if (colors.isEmpty()) {
            colors.add(TextColor.color(0xffffff));
            colors.add(TextColor.color(0x000000));
        }
        float steps = 1f / (colors.size() - 1);
        for (int colorIndex = 1; colorIndex < colors.size(); colorIndex++) {
            float value = colorIndex * steps;
            if (value >= phase) {
                float factor = 1 + (phase - value) * (colors.size() - 1);
                return negativePhase
                        ? TextColor.lerp(1 - factor, colors.get(colorIndex), colors.get(colorIndex - 1))
                        : TextColor.lerp(factor, colors.get(colorIndex - 1), colors.get(colorIndex));
            }
        }
        return colors.getFirst();
    }

    /**
     * {@code <shadow:#rrggbbaa>}, {@code <shadow:color[:alpha]>} (alpha 0-1, 0.25 by default), {@code <!shadow>}: built
     * by the bridge, as the type only exists from Adventure 4.18.
     */
    private static @NotNull Style shadow(@NotNull String name, @NotNull List<String> arguments) {
        AdventureBridge bridge = AdventureBridges.current();
        Style style;
        if (name.equals("!shadow")) {
            if (!arguments.isEmpty()) {
                throw unsupported();
            }
            style = bridge.shadow(null, 0);
        } else {
            if (arguments.isEmpty() || arguments.size() > 2) {
                throw unsupported();
            }
            String color = arguments.getFirst().toLowerCase(Locale.ROOT);
            if (color.startsWith("#") && color.length() == 9) {
                if (arguments.size() != 1) {
                    throw unsupported();
                }
                int argb;
                try {
                    argb = (int) Long.parseLong(color.substring(1), 16);
                } catch (NumberFormatException e) {
                    throw unsupported();
                }
                style = bridge.shadow(TextColor.color(argb >>> 8), argb & 0xff);
            } else {
                TextColor text = color(color);
                if (text == null) {
                    throw unsupported();
                }
                float alpha = 0.25f;
                if (arguments.size() == 2) {
                    try {
                        alpha = (float) Double.parseDouble(arguments.get(1));
                    } catch (NumberFormatException e) {
                        throw unsupported();
                    }
                }
                style = bridge.shadow(text, (int) (alpha * 0xff));
            }
        }
        if (style == null) {
            throw unsupported();
        }
        return style;
    }

    /**
     * Splits an argument at its inline markers. A value touching a character that could join it into a legacy code
     * or a hex colour ({@code &}, {@code §}, {@code #}, a hex digit, another value) would be read differently once
     * written in: such a message keeps the stage 1 path.
     */
    private @NotNull Argument argument(@NotNull String text, char before) {
        List<Object> pieces = new ArrayList<>();
        int from = 0;
        int start;
        while ((start = text.indexOf(Engine.INLINE_START, from)) >= 0) {
            int end = text.indexOf(Engine.INLINE_END, start);
            if (end < 0) {
                throw unsupported();
            }
            char previous = start == 0 ? before : text.charAt(start - 1);
            char next = end + 1 < text.length() ? text.charAt(end + 1) : ' ';
            if (joins(previous) || joins(next) || previous == Engine.INLINE_END || next == Engine.INLINE_START) {
                throw unsupported();
            }
            if (start > from) {
                pieces.add(text.substring(from, start));
            }
            int index = Engine.parseIndex(text, start + 1, end);
            if (index < 0) {
                throw unsupported();
            }
            pieces.add(new Inline(index, previous));
            from = end + 1;
        }
        if (from < text.length()) {
            pieces.add(text.substring(from));
        }
        return new Argument(List.copyOf(pieces), before != ':', this.legacyCodes);
    }

    private static boolean joins(char c) {
        return c == '&' || c == '§' || c == '#' || Character.digit(c, 16) >= 0;
    }

    /**
     * {@code <gradient:color:color...[:phase]>}: two colours or more (white to black without any), the last argument
     * may be a phase in [-1, 1].
     */
    private static @NotNull ColorChange gradient(@NotNull List<String> arguments) {
        List<TextColor> colors = new ArrayList<>(arguments.size());
        double phase = 0;
        for (int i = 0; i < arguments.size(); i++) {
            String argument = arguments.get(i);
            TextColor color = color(argument);
            if (color != null) {
                colors.add(color);
                continue;
            }
            if (i != arguments.size() - 1) {
                throw unsupported();
            }
            try {
                phase = Double.parseDouble(argument);
            } catch (NumberFormatException e) {
                throw unsupported();
            }
            if (!(phase >= -1d && phase <= 1d)) {
                throw unsupported();
            }
        }
        if (colors.size() == 1) {
            throw unsupported();
        }
        if (colors.isEmpty()) {
            colors.add(TextColor.color(0xffffff));
            colors.add(TextColor.color(0x000000));
        }
        if (phase < 0) {
            phase = 1 + phase;
            Collections.reverse(colors);
        }
        return new ColorChange.Gradient(colors.toArray(new TextColor[0]), phase);
    }

    /**
     * {@code <rainbow>}, {@code <rainbow:!>} (reversed), {@code <rainbow:3>}, {@code <rainbow:!3>} (phase in tenths).
     */
    private static @NotNull ColorChange rainbow(@NotNull List<String> arguments) {
        if (arguments.size() > 1) {
            throw unsupported();
        }
        boolean reversed = false;
        int phase = 0;
        if (!arguments.isEmpty()) {
            String value = arguments.getFirst();
            if (value.startsWith("!")) {
                reversed = true;
                value = value.substring(1);
            }
            if (!value.isEmpty()) {
                try {
                    phase = Integer.parseInt(value);
                } catch (NumberFormatException e) {
                    throw unsupported();
                }
            }
        }
        return new ColorChange.Rainbow(reversed, phase / 10d);
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
        try {
            switch (arguments.getFirst()) {
                case "run_command" -> style.clickEvent(ClickEvent.runCommand(value));
                case "suggest_command" -> style.clickEvent(ClickEvent.suggestCommand(value));
                case "open_url" -> style.clickEvent(ClickEvent.openUrl(value));
                case "copy_to_clipboard" -> style.clickEvent(ClickEvent.copyToClipboard(value));
                default -> throw unsupported();
            }
        } catch (IllegalArgumentException e) {
            throw unsupported(); // Adventure 5.1+ refuses an invalid URL: MiniMessage decides what that gives
        }
        return style.build();
    }

    private static final Set<String> CLICK_ACTIONS = Set.of("run_command", "suggest_command", "open_url", "copy_to_clipboard");

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
        List<Character> before = new ArrayList<>(2);
        while (i < length && text.charAt(i) == ':') {
            i++;
            if (i < length && (text.charAt(i) == '\'' || text.charAt(i) == '"')) {
                char quote = text.charAt(i++);
                before.add(quote);
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
                before.add(':');
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
        return new Tag(name, List.copyOf(arguments), List.copyOf(before), closing, start, i + 1);
    }

    private static boolean isNameChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_' || c == '-';
    }
}
