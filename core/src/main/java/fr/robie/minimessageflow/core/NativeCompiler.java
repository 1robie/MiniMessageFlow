package fr.robie.minimessageflow.core;

import fr.robie.minimessageflow.ModifierSet;
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
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Compiles a message without MiniMessage, when every tag in it is one of the native set: colours ({@code <red>},
 * {@code <#ff00aa>}, {@code <color:...>}), decorations and their negations, {@code <reset>}, {@code <newline>},
 * {@code <click>}, {@code <hover>} ({@code show_text}, {@code show_item} with a key and count, {@code show_entity}),
 * {@code <insert>}, {@code <font>}, {@code <key>}, {@code <lang>} and its aliases, {@code <gradient>},
 * {@code <rainbow>}, {@code <transition>}, placeholders, and where the bridge builds them {@code <shadow>},
 * {@code <sprite>} and {@code <head>}. Any other tag ({@code <selector>}, {@code <score>}, {@code <nbt>}, a user
 * tag...) or a form of these the versions read differently, and the message is left to MiniMessage: {@link #compile}
 * returns null.
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
    /**
     * A player name that is not a UUID, a texture key or a hat argument in any version: 1-16 letters, digits or _ ,
     * and not true, on, false or off (the hat argument, read with exact case).
     */
    private static final java.util.regex.Pattern PLAYER_NAME = java.util.regex.Pattern.compile("(?!(?:true|false|on|off)$)[A-Za-z0-9_]{1,16}");
    private static final String[] NO_INLINE = new String[0];
    private static final char LEGACY_SECTION = 0xA7;
    private static final char BACKSLASH = 0x5C;

    private final TagResolver standard;
    private final @Nullable TagResolver userTags;
    private final ModifierSet modifiers;
    private final Engine.Slots slots;
    private final Set<Integer> unclaimed;
    private final boolean discover;

    private NativeCompiler(@NotNull TagResolver standard, @Nullable TagResolver userTags, @NotNull ModifierSet modifiers, @NotNull Engine.Slots slots,
                           @NotNull Set<Integer> unclaimed, boolean discover) {
        this.standard = standard;
        this.userTags = userTags;
        this.modifiers = modifiers;
        this.slots = slots;
        this.unclaimed = unclaimed;
        this.discover = discover;
    }

    /**
     * @param text the message with slot tags, legacy codes already converted; it may hold inline markers
     *             ({@link Engine#INLINE_START}) in click and insert arguments
     * @return the parts, or null when the message needs MiniMessage
     */
    static @Nullable List<Part> compile(@NotNull String text, @NotNull TagResolver standard, @Nullable TagResolver userTags, @NotNull ModifierSet modifiers,
                                        @NotNull Engine.Slots slots, @NotNull Set<Integer> unclaimed) {
        return compile(text, standard, userTags, modifiers, slots, unclaimed, false);
    }

    /**
     * @param discover whether {@code <key>} tags not yet in {@code slots} are added to it (the native compile of a new
     *                 message; {@code slots} must then be a copy that is dropped when this returns null)
     */
    static @Nullable List<Part> compile(@NotNull String text, @NotNull TagResolver standard, @Nullable TagResolver userTags, @NotNull ModifierSet modifiers,
                                        @NotNull Engine.Slots slots, @NotNull Set<Integer> unclaimed, boolean discover) {
        return compile(text, standard, userTags, modifiers, slots, unclaimed, discover, null);
    }

    /**
     * @param reason where the reason is put (at 0) when this returns null; may be null
     */
    static @Nullable List<Part> compile(@NotNull String text, @NotNull TagResolver standard, @Nullable TagResolver userTags, @NotNull ModifierSet modifiers,
                                        @NotNull Engine.Slots slots, @NotNull Set<Integer> unclaimed, boolean discover,
                                        @Nullable String @Nullable [] reason) {
        NativeCompiler compiler = new NativeCompiler(standard, userTags, modifiers, slots, unclaimed, discover);
        try {
            Element root = compiler.parse(text, true);
            return merge(reduce(root.children, Style.empty()));
        } catch (Unsupported e) {
            if (reason != null) {
                reason[0] = compiler.reason != null ? "the tag " + compiler.reason
                        : "text or tags the native compiler leaves to MiniMessage";
            }
            return null;
        }
    }

    /**
     * The tag being read when the message was given up, as written.
     */
    private @Nullable String reason;

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
        record Value(int slot, @NotNull Style style, @NotNull Styled styled) implements Part {
        }

        /**
         * A click, hover or insertion (with the style around it) over parts that hold values.
         *
         * @param shell the component the parts go into, built once: empty text with the style, or the keybind or
         *              translatable the tag inserts
         */
        record Group(@NotNull Style style, @NotNull List<Part> parts, @NotNull Component shell) implements Part {
        }

        /**
         * A click or insertion whose argument holds values, built at render, over its parts.
         */
        record Event(@NotNull Style style, @NotNull String action, @NotNull Argument argument, @NotNull List<Part> parts) implements Part {
        }

        /**
         * A {@code show_text} hover whose text holds values, built at render, over its parts.
         */
        record Hover(@NotNull Style style, @NotNull InlineText text, @NotNull List<Part> parts) implements Part {
        }

        /**
         * A translatable with values in its arguments, built at render; its parts go into it.
         *
         * @param arguments each a {@code Component} (no value in it) or an {@link InlineText}
         */
        record Translatable(@NotNull Style style, @NotNull String key, @Nullable String fallback, @NotNull List<Object> arguments,
                            @NotNull List<Part> parts) implements Part {
        }

        /**
         * A gradient or rainbow over text and values, coloured at render.
         */
        record Transform(@NotNull Style style, @NotNull ColorChange change, @NotNull List<Item> items,
                         @NotNull Palettes palettes) implements Part {
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
    record Argument(@NotNull List<Object> pieces, boolean quoted) {
        /**
         * @return the argument, or null when a value would change how MiniMessage splits the tag (a {@code :} in an
         * argument without quotes): that render goes the stage 1 way
         */
        @Nullable String render(@NotNull String @NotNull [] inline) {
            List<Object> pieces = this.pieces;
            if (pieces.size() <= 2) {
                // The usual shapes, without a builder: a value alone, or text and a value.
                String text = "";
                for (int i = 0; i < pieces.size(); i++) {
                    Object piece = pieces.get(i);
                    String part;
                    if (piece instanceof Inline(int index)) {
                        part = inline[index];
                        if (!this.quoted && part.indexOf(':') >= 0) {
                            return null;
                        }
                    } else {
                        part = (String) piece;
                    }
                    text = i == 0 ? part : text.concat(part);
                }
                return text;
            }
            StringBuilder out = new StringBuilder(32);
            for (Object piece : this.pieces) {
                if (piece instanceof Inline(int index)) {
                    String value = inline[index];
                    if (!this.quoted && value.indexOf(':') >= 0) {
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
     * A tag argument read as MiniMessage (a hover's text, a translation argument) with inline values in its text,
     * built again at each render.
     *
     * @param inline  the inline values in it
     * @param quoted  whether the argument was written in quotes
     * @param compact whether MiniMessage's result is compacted (a translation argument)
     * @param runs    the text as runs, each a {@link Run} or a fixed {@code Component} with the style around it, when
     *                only what it shows matters (a hover's text) and it is made of text under styling tags; null
     *                otherwise
     */
    record InlineText(@NotNull Element root, int @NotNull [] inline, boolean quoted, boolean compact, @Nullable List<Object> runs) {
        /**
         * @return the component, or null when a value could be read otherwise by MiniMessage, were it written into
         * the text: a {@code :} in an argument without quotes (it splits the tag), a {@code #} (a colour, with legacy
         * codes on). That render goes the stage 1 way.
         */
        @Nullable Component render(@NotNull String @NotNull [] values) {
            for (int index : this.inline) {
                String value = values[index];
                if (value.indexOf('#') >= 0 || (!this.quoted && value.indexOf(':') >= 0)) {
                    return null;
                }
            }
            if (this.runs != null) {
                return this.renderRuns(values);
            }
            // The common shape, one run of text under its tags: one component, which is also what compact() gives.
            Component single = this.root.single(values, Style.empty());
            if (single != null) {
                return single;
            }
            Component built = this.root.build(values);
            return this.compact ? built.compact() : built;
        }

        private @NotNull Component renderRuns(@NotNull String @NotNull [] values) {
            List<Object> runs = Objects.requireNonNull(this.runs);
            int size = runs.size();
            if (size == 1) {
                return runs.getFirst() instanceof Run run ? Component.text(run.text(values), run.style())
                        : (Component) runs.getFirst();
            }
            Component[] shown = new Component[size];
            for (int i = 0; i < size; i++) {
                shown[i] = runs.get(i) instanceof Run run ? Component.text(run.text(values), run.style())
                        : (Component) runs.get(i);
            }
            return Component.empty().children(Arrays.asList(shown));
        }
    }

    /**
     * Text shown with one style: literal pieces ({@code String}) and inline values ({@code Integer}, their number).
     */
    record Run(@NotNull Style style, @NotNull Object @NotNull [] pieces) {
        @NotNull String text(@NotNull String @NotNull [] values) {
            Object[] pieces = this.pieces;
            if (pieces.length == 1) {
                return piece(pieces[0], values);
            }
            StringBuilder out = new StringBuilder(32);
            for (Object piece : pieces) {
                out.append(piece(piece, values));
            }
            return out.toString();
        }

        private static @NotNull String piece(@NotNull Object piece, @NotNull String @NotNull [] values) {
            return piece instanceof Integer index ? values[index] : (String) piece;
        }
    }

    /**
     * Inline value number {@code index}.
     */
    record Inline(int index) {
    }

    /**
     * Whether a gradient or rainbow is coloured at render: its colours depend on the values' length.
     */
    static boolean hasTransform(@NotNull List<Part> parts) {
        for (Part part : parts) {
            if (part instanceof Part.Transform
                    || part instanceof Part.Group(Style style, List<Part> children, Component base) && hasTransform(children)
                    || part instanceof Part.Event event && hasTransform(event.parts())
                    || part instanceof Part.Hover hover && hasTransform(hover.parts())
                    || part instanceof Part.Translatable translatable && hasTransform(translatable.parts())) {
                return true;
            }
        }
        return false;
    }

    /**
     * @param values the value of each slot: a {@code String} (plain text), a {@code Component}, or null for none
     */
    static @Nullable Component render(@NotNull List<Part> parts, @Nullable Object @NotNull [] values) {
        return render(parts, values, NO_INLINE);
    }

    /**
     * @return the component, or null when a gradient meets a value it can't colour the way MiniMessage does (a value
     * with a style or children): such a render goes through MiniMessage
     */
    static @Nullable Component render(@NotNull List<Part> parts, @Nullable Object @NotNull [] values, @NotNull String @NotNull [] inline) {
        List<Component> children = renderParts(parts, values, inline);
        if (children == null) {
            return null;
        }
        // One part is the message: no empty component around it.
        return children.size() == 1 ? children.getFirst() : Component.empty().children(children);
    }

    private static @Nullable List<Component> renderParts(@NotNull List<Part> parts, @Nullable Object @NotNull [] values,
                                                         @NotNull String @NotNull [] inline) {
        List<Component> out = new ArrayList<>(parts.size());
        for (Part part : parts) {
            switch (part) {
                case Part.Fixed(Component component) -> out.add(component);
                case Part.Value(int slot, Style style, Styled memo) -> {
                    Object value = values[slot];
                    if (value instanceof String text) {
                        // Text goes straight into its component, with the style around it.
                        out.add(style.isEmpty() ? Component.text(text) : Component.text(text, style));
                    } else if (value != null) {
                        out.add(style.isEmpty() ? (Component) value : memo.apply((Component) value, style));
                    }
                }
                case Part.Group(Style style, List<Part> children, Component shell) -> {
                    List<Component> rendered = renderParts(children, values, inline);
                    if (rendered == null) {
                        return null;
                    }
                    out.add(shell.children(rendered));
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
                    Style event = withEvent(style, action, value);
                    if (event == null) {
                        return null;
                    }
                    out.add(Component.text("", event).children(rendered));
                }
                case Part.Hover(Style style, InlineText text, List<Part> children) -> {
                    List<Component> rendered = renderParts(children, values, inline);
                    Component hover = rendered == null ? null : text.render(inline);
                    if (hover == null) {
                        return null;
                    }
                    out.add(Component.text("", style.hoverEvent(HoverEvent.showText(hover))).children(rendered));
                }
                case Part.Translatable(Style style, String key, String fallback, List<Object> arguments, List<Part> children) -> {
                    List<Component> rendered = renderParts(children, values, inline);
                    if (rendered == null) {
                        return null;
                    }
                    List<Component> with = new ArrayList<>(arguments.size());
                    for (Object argument : arguments) {
                        Component component = argument instanceof InlineText text ? text.render(inline) : (Component) argument;
                        if (component == null) {
                            return null;
                        }
                        with.add(component);
                    }
                    Component base = fallback == null ? Component.translatable(key, with) : Component.translatable(key, fallback, with);
                    out.add(base.style(style).children(rendered));
                }
                case Part.Transform(Style style, ColorChange change, List<Item> items, Palettes palettes) -> {
                    Component colored = transform(style, change, items, values, palettes);
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
     * Remembers, for one place in a message, the last component value put under its style, when the same component
     * comes again: a resolver's text (parsed once and kept), a constant, a component kept by the caller. Such a
     * value is then not styled again on each render.
     * <p>
     * Shared by the threads that render. A {@link Kept} is immutable; two threads may each make one, and either is
     * right. A value is only kept the second time it is seen in a row, so values that change at each render cost
     * nothing more than a field written.
     */
    static final class Styled {
        private record Kept(@NotNull Component value, @NotNull Component styled) {
        }

        private @Nullable Component seen;
        private @Nullable Kept kept;

        @NotNull Component apply(@NotNull Component value, @NotNull Style style) {
            Kept kept = this.kept;
            if (kept != null && kept.value() == value) {
                return kept.styled();
            }
            Component styled = styled(value, style);
            if (this.seen == value) {
                this.kept = new Kept(value, styled);
            } else {
                this.seen = value;
            }
            return styled;
        }
    }

    /**
     * A style with the click or insertion of an {@link Part.Event} on it.
     *
     * @param action the action in lower case, or {@code insert}
     * @return the style, or null when the event can't be built here: this render is MiniMessage's
     */
    private static @Nullable Style withEvent(@NotNull Style style, @NotNull String action, @NotNull String value) {
        try {
            return switch (action) {
                case "insert" -> style.insertion(value);
                case "run_command" -> style.clickEvent(ClickEvent.runCommand(value));
                case "suggest_command" -> style.clickEvent(ClickEvent.suggestCommand(value));
                case "open_url" -> style.clickEvent(ClickEvent.openUrl(value));
                case "copy_to_clipboard" -> style.clickEvent(ClickEvent.copyToClipboard(value));
                default -> click(List.of(action, value)).merge(style, Style.Merge.Strategy.IF_ABSENT_ON_TARGET);
            };
        } catch (RuntimeException e) {
            // Adventure 5.1+ checks a URL when the event is built, and a page must be a number: MiniMessage
            // decides what an invalid one gives.
            return null;
        }
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

    /**
     * The one-character strings of ASCII, so a gradient makes none for them.
     */
    private static final String[] ASCII = new String[128];

    static {
        for (int i = 0; i < ASCII.length; i++) {
            ASCII[i] = String.valueOf((char) i);
        }
    }

    private static @NotNull String character(int codePoint) {
        return codePoint < ASCII.length ? ASCII[codePoint] : Character.toString(codePoint);
    }

    /**
     * What a gradient or rainbow has already worked out, by the length of what it colours (the colours depend on
     * nothing else): each position's colour, and the finished characters of the text before the first value and
     * after the last one, which are at the same positions for a given length. Lengths under {@link #CACHED} are kept.
     * <p>
     * Shared by the threads that render: a {@link Palette} is immutable, and two threads may each make the same one.
     */
    static final class Palettes {
        private static final int CACHED = 64;

        /**
         * The items before the first value, and from the one after the last value on.
         */
        private final int lead;
        private final int trailStart;
        private final Palette[] byLength = new Palette[CACHED];
        /**
         * The component the characters go into, with the style around the gradient.
         */
        private final Component shell;

        Palettes(@NotNull List<Item> items, @NotNull Style style) {
            this.shell = Component.text("", style);
            int lead = 0;
            while (lead < items.size() && items.get(lead).text() != null) {
                lead++;
            }
            int trailStart = items.size();
            while (trailStart > lead && items.get(trailStart - 1).text() != null) {
                trailStart--;
            }
            this.lead = lead;
            this.trailStart = trailStart;
        }

        @NotNull Palette of(@NotNull ColorChange change, @NotNull List<Item> items, int length) {
            Palette palette = length < CACHED ? this.byLength[length] : null;
            if (palette == null) {
                palette = new Palette(change, items, this.lead, this.trailStart, length);
                if (length < CACHED) {
                    this.byLength[length] = palette;
                }
            }
            return palette;
        }
    }

    /**
     * For one length: the style that is only each position's colour, and the characters that never change.
     */
    static final class Palette {
        final Style[] colors;
        final Component[] leading;
        final Component[] trailing;

        Palette(@NotNull ColorChange change, @NotNull List<Item> items, int lead, int trailStart, int length) {
            this.colors = new Style[length];
            Cursor cursor = change.start(length);
            for (int i = 0; i < length; i++) {
                this.colors[i] = Style.style(cursor.next());
            }
            this.leading = this.characters(items, 0, lead, 0);
            int trailLength = 0;
            for (int i = trailStart; i < items.size(); i++) {
                String text = Objects.requireNonNull(items.get(i).text());
                trailLength += text.codePointCount(0, text.length());
            }
            this.trailing = this.characters(items, trailStart, items.size(), length - trailLength);
        }

        private @NotNull Component @NotNull [] characters(@NotNull List<Item> items, int from, int to, int position) {
            List<Component> out = new ArrayList<>();
            for (int i = from; i < to; i++) {
                Item item = items.get(i);
                String text = Objects.requireNonNull(item.text());
                for (int offset = 0; offset < text.length(); ) {
                    int codePoint = text.codePointAt(offset);
                    offset += Character.charCount(codePoint);
                    out.add(Component.text(character(codePoint), this.style(item.style(), position++)));
                }
            }
            return out.toArray(new Component[0]);
        }

        @NotNull Style style(@NotNull Style itemStyle, int position) {
            Style color = this.colors[position];
            return itemStyle.isEmpty() ? color : itemStyle.color(color.color());
        }
    }

    private static @Nullable Component transform(@NotNull Style style, @NotNull ColorChange change, @NotNull List<Item> items,
                                                 @Nullable Object @NotNull [] values, @NotNull Palettes palettes) {
        int lead = palettes.lead;
        int trailStart = palettes.trailStart;
        // The values' texts, and the length of everything.
        String[] texts = trailStart > lead ? new String[trailStart - lead] : null;
        int length = 0;
        for (int i = 0; i < items.size(); i++) {
            Item item = items.get(i);
            String text = item.text();
            if (text == null) {
                Object value = values[item.slot()];
                if (value instanceof String string) {
                    text = string;
                } else if (value instanceof TextComponent component && component.style().isEmpty() && component.children().isEmpty()) {
                    text = component.content();
                } else {
                    return null;
                }
            }
            if (i >= lead && i < trailStart) {
                texts[i - lead] = text;
            }
            length += text.codePointCount(0, text.length());
        }
        Palette palette = palettes.of(change, items, length);
        Component[] chars = new Component[length];
        int position = palette.leading.length;
        System.arraycopy(palette.leading, 0, chars, 0, position);
        for (int i = lead; i < trailStart; i++) {
            Style itemStyle = items.get(i).style();
            String text = texts[i - lead];
            for (int offset = 0; offset < text.length(); ) {
                int codePoint = text.codePointAt(offset);
                offset += Character.charCount(codePoint);
                chars[position] = Component.text(character(codePoint), palette.style(itemStyle, position));
                position++;
            }
        }
        System.arraycopy(palette.trailing, 0, chars, position, palette.trailing.length);
        return palettes.shell.children(Arrays.asList(chars));
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
                case Node.Insert(Component component) -> parts.add(new Part.Fixed(styled(component, inherited)));
                case Node.Slot(int slot) -> parts.add(new Part.Value(slot, inherited, new Styled()));
                case Node.InlineText ignored -> throw unsupported(); // only in a tag argument, never reduced
                case Element element -> {
                    Style style = element.style.merge(inherited, Style.Merge.Strategy.IF_ABSENT_ON_TARGET);
                    if (!element.isDynamic()) {
                        if (element.hasText()) { // <bold><hover:...><reset> shows nothing: no part
                            parts.add(new Part.Fixed(element.build().style(style)));
                        }
                    } else if (element.change != null) {
                        List<Item> items = items(element.children, Style.empty());
                        parts.add(new Part.Transform(style, element.change, items, new Palettes(items, style)));
                    } else if (element.dynamic instanceof InlineText text) {
                        parts.add(new Part.Hover(style, text, merge(reduce(element.children, Style.empty()))));
                    } else if (element.dynamic instanceof DynamicTranslatable(String key, String fallback, List<Object> arguments)) {
                        parts.add(new Part.Translatable(style, key, fallback, arguments, merge(reduce(element.children, Style.empty()))));
                    } else if (element.action != null) {
                        parts.add(new Part.Event(style, element.action.toLowerCase(Locale.ROOT), element.argument,
                                merge(reduce(element.children, Style.empty()))));
                    } else if (hasEvents(element.style) || element.base != null) {
                        // An event stays on its parent; a keybind or translatable wraps what follows it.
                        parts.add(new Part.Group(style, merge(reduce(element.children, Style.empty())),
                                element.base == null ? Component.text("", style) : element.base.style(style)));
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
                case Node.Insert insert -> throw unsupported(); // MiniMessage colours an object component as one character
                case Node.Slot(int slot) -> items.add(new Item(null, slot, inherited));
                case Node.InlineText ignored -> throw unsupported();
                case Element element -> {
                    if (element.change != null || element.action != null || element.base != null || element.dynamic != null
                            || element.style.color() != null) {
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
        // Compacted here, once: what a render takes as it is has no component more than it needs.
        merged.add(new Part.Fixed((run.size() == 1 ? run.getFirst() : Component.empty().children(run)).compact()));
        run.clear();
    }

    // --- parsing ------------------------------------------------------------------------------------------------

    private sealed interface Node permits Node.Text, Node.Slot, Node.Insert, Node.InlineText, Element {
        record Text(@NotNull String text) implements Node {
        }

        /**
         * A component a self-closing tag puts here ({@code <sprite>}, {@code <head>}).
         */
        record Insert(@NotNull Component component) implements Node {
        }

        record Slot(int slot) implements Node {
        }

        /**
         * Inline value number {@code index}, as text: only in a tag argument read as MiniMessage.
         */
        record InlineText(int index) implements Node {
        }
    }

    /**
     * What makes a translatable with values in its arguments.
     */
    private record DynamicTranslatable(@NotNull String key, @Nullable String fallback, @NotNull List<Object> arguments) {
    }

    /**
     * A tag that wraps what follows it until it is closed.
     */
    static final class Element implements Node {
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

        /**
         * What is built at render besides a click or an insertion: an {@link NativeCompiler.InlineText} (a hover's
         * text) or a {@link DynamicTranslatable}; null for none.
         */
        final @Nullable Object dynamic;

        Element(@NotNull String name, @NotNull Style style) {
            this(name, style, null, null, null, null);
        }

        Element(@NotNull String name, @NotNull Object dynamic) {
            this(name, Style.empty(), null, null, null, null, dynamic);
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
            this(name, style, action, argument, change, base, null);
        }

        private Element(@NotNull String name, @NotNull Style style, @Nullable String action, @Nullable Argument argument,
                        @Nullable ColorChange change, @Nullable Component base, @Nullable Object dynamic) {
            this.dynamic = dynamic;
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
            if (this.action != null || this.dynamic != null) {
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
            if (this.base != null || this.dynamic instanceof DynamicTranslatable) {
                return true; // a keybind or translatable shows something
            }
            for (Node child : this.children) {
                if (child instanceof Node.Text(String text) && !text.isEmpty() || child instanceof Node.Insert
                        || child instanceof Element element && element.hasText()) {
                    return true;
                }
            }
            return false;
        }

        @NotNull Component build() {
            return this.build(NO_INLINE);
        }

        /**
         * Built once, for a part of a tag argument that holds no inline value (set when the argument is compiled).
         */
        private @Nullable Component fixed;

        /**
         * This element as one text component, when it is only text and inline values under styling tags; null
         * otherwise, or when the text is empty.
         */
        @Nullable Component single(@NotNull String @NotNull [] inline, @NotNull Style inherited) {
            if (this.change != null || this.base != null || this.dynamic != null || this.action != null) {
                return null;
            }
            Style style = inherited.isEmpty() ? this.style : this.style.merge(inherited, Style.Merge.Strategy.IF_ABSENT_ON_TARGET);
            List<Node> nodes = this.children;
            int size = nodes.size();
            if (size == 1 && nodes.getFirst() instanceof Element only) {
                return only.single(inline, style);
            }
            String text = null;
            StringBuilder joined = null;
            for (int i = 0; i < size; i++) {
                String piece;
                switch (nodes.get(i)) {
                    case Node.Text(String value) -> piece = value;
                    case Node.InlineText(int index) -> piece = inline[index];
                    default -> {
                        return null;
                    }
                }
                if (text == null) {
                    text = piece;
                } else {
                    if (joined == null) {
                        joined = new StringBuilder(text.length() + piece.length() + 16).append(text);
                    }
                    joined.append(piece);
                }
            }
            if (joined != null) {
                text = joined.toString();
            }
            return text == null || text.isEmpty() ? null : Component.text(text, style);
        }

        /**
         * @param inline the inline values, for a tag argument with {@link Node.InlineText} in it: a value and the text
         *               around it make one text, as when the value is written into the argument and parsed
         */
        @NotNull Component build(@NotNull String @NotNull [] inline) {
            if (this.fixed != null) {
                return this.fixed;
            }
            if (this.change != null) {
                List<Item> items = items(this.children, Style.empty());
                Component colored = transform(this.style, this.change, items, new Component[0], new Palettes(items, this.style));
                if (colored == null) {
                    throw new IllegalStateException("value in a fixed gradient");
                }
                return colored;
            }
            List<Component> built = new ArrayList<>(this.children.size());
            StringBuilder joined = null;
            for (Node child : this.children) {
                if (inline.length != 0 && (child instanceof Node.Text || child instanceof Node.InlineText)) {
                    if (joined == null) {
                        joined = new StringBuilder();
                    }
                    joined.append(child instanceof Node.Text(String text) ? text : inline[((Node.InlineText) child).index()]);
                    continue;
                }
                if (joined != null) {
                    built.add(Component.text(joined.toString()));
                    joined = null;
                }
                switch (child) {
                    case Node.Text(String text) -> built.add(Component.text(text));
                    case Node.Insert(Component component) -> built.add(component);
                    case Element element -> built.add(element.build(inline));
                    case Node.Slot slot -> throw new IllegalStateException("slot in a fixed part");
                    case Node.InlineText text -> throw new IllegalStateException("inline value in a fixed part");
                }
            }
            if (joined != null) {
                built.add(Component.text(joined.toString()));
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
        return this.parse(text, slotsAllowed, null);
    }

    /**
     * A tag argument read as MiniMessage, with inline values in its text.
     */
    private @NotNull InlineText inlineText(@NotNull String argument, char before, boolean compact) {
        List<Integer> found = new ArrayList<>(2);
        Element root = this.parse(argument, false, found);
        requireInlineInPlainText(root, false);
        int[] inline = new int[found.size()];
        for (int i = 0; i < inline.length; i++) {
            inline[i] = found.get(i);
        }
        List<Object> runs = null;
        if (!compact) {
            List<Object> flat = new ArrayList<>();
            if (flatten(root, Style.empty(), flat)) {
                runs = new ArrayList<>(flat.size());
                for (Object item : flat) {
                    runs.add(item instanceof RunBuilder builder ? builder.build() : item);
                }
                runs = List.copyOf(runs);
            }
        }
        return new InlineText(root, inline, before != ':', compact, runs);
    }

    private static final class RunBuilder {
        final Style style;
        final List<Object> pieces = new ArrayList<>(2);

        RunBuilder(@NotNull Style style) {
            this.style = style;
        }

        void add(@NotNull Object piece) {
            if (piece instanceof String text && !this.pieces.isEmpty() && this.pieces.getLast() instanceof String last) {
                this.pieces.set(this.pieces.size() - 1, last + text);
            } else {
                this.pieces.add(piece);
            }
        }

        @NotNull Run build() {
            return new Run(this.style, this.pieces.toArray());
        }
    }

    /**
     * A tag argument as runs of text, each with the style of the tags around it: what it shows, without its tree.
     *
     * @return false when something in it is not text under a styling tag
     */
    private static boolean flatten(@NotNull Element element, @NotNull Style inherited, @NotNull List<Object> out) {
        if (element.fixed != null) {
            out.add(styled(element.fixed, inherited));
            return true;
        }
        if (element.change != null || element.base != null || element.dynamic != null || element.action != null) {
            return false;
        }
        Style style = element.style.merge(inherited, Style.Merge.Strategy.IF_ABSENT_ON_TARGET);
        for (Node child : element.children) {
            switch (child) {
                case Node.Text(String text) -> piece(out, style, text);
                case Node.InlineText(int index) -> piece(out, style, index);
                case Node.Insert(Component component) -> out.add(styled(component, style));
                case Element nested -> {
                    if (!flatten(nested, style, out)) {
                        return false;
                    }
                }
                case Node.Slot ignored -> {
                    return false;
                }
            }
        }
        return true;
    }

    private static void piece(@NotNull List<Object> out, @NotNull Style style, @NotNull Object piece) {
        if (!out.isEmpty() && out.getLast() instanceof RunBuilder last && last.style.equals(style)) {
            last.add(piece);
        } else {
            RunBuilder builder = new RunBuilder(style);
            builder.add(piece);
            out.add(builder);
        }
    }

    /**
     * An inline value under a gradient or rainbow would change its colours: left to MiniMessage.
     */
    private static boolean requireInlineInPlainText(@NotNull Element element, boolean underChange) {
        boolean any = false;
        for (Node child : element.children) {
            if (child instanceof Node.InlineText) {
                any = true;
            } else if (child instanceof Element nested) {
                boolean inside = requireInlineInPlainText(nested, underChange || nested.change != null);
                if (!inside) {
                    nested.fixed = nested.build();
                }
                any |= inside;
            }
        }
        if (any && (underChange || element.change != null)) {
            throw unsupported();
        }
        return any;
    }

    /**
     * @param inline where the inline values found in the text are added (a tag argument read as MiniMessage); null
     *               when the text may hold none
     */
    private @NotNull Element parse(@NotNull String text, boolean slotsAllowed, @Nullable List<Integer> inline) {
        Element root = new Element("", Style.empty());
        Deque<Element> open = new ArrayDeque<>();
        open.push(root);
        StringBuilder pending = new StringBuilder();
        int length = text.length();
        int i = 0;
        while (i < length) {
            char c = text.charAt(i);
            if (c == Engine.INLINE_START) {
                int end = text.indexOf(Engine.INLINE_END, i);
                if (inline == null || end < 0) {
                    throw unsupported(); // an inline value outside a tag argument
                }
                // Written into the text, a value after one of these could make a legacy code with it ("&" + "c",
                // "Issue #" + "123456"): MiniMessage's.
                for (int back = i - 1; back >= 0 && back >= i - 14; back--) {
                    char before = text.charAt(back);
                    if (before == '&' || before == LEGACY_SECTION || before == '#' || before == BACKSLASH) {
                        throw unsupported();
                    }
                }
                int index = Engine.parseIndex(text, i + 1, end);
                if (index < 0) {
                    throw unsupported();
                }
                flush(pending, open.peek());
                open.peek().children.add(new Node.InlineText(index));
                inline.add(index);
                i = end + 1;
                continue;
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
                    try {
                        this.handle(tag, text, open, pending, slotsAllowed);
                    } catch (Unsupported e) {
                        if (this.reason == null) {
                            this.reason = "<" + tag.name() + ">"; // by name: the text here has slot tags and markers in it
                        }
                        throw e;
                    }
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
            case "sprite" -> {
                List<String> arguments = tag.arguments();
                if (arguments.isEmpty() || arguments.size() > 2) {
                    throw unsupported();
                }
                Component sprite = arguments.size() == 1
                        ? AdventureBridges.current().sprite(null, arguments.getFirst())
                        : AdventureBridges.current().sprite(arguments.getFirst(), arguments.get(1));
                if (sprite == null) {
                    throw unsupported();
                }
                flush(pending, current);
                current.children.add(new Node.Insert(sprite));
            }
            case "head" -> {
                // Only the forms every version reads the same way: the viewer's head, or a plain player name (5.2
                // reads UUIDs and checks names differently from 4.26; textures and the hat argument stay with
                // MiniMessage).
                List<String> arguments = tag.arguments();
                if (arguments.size() > 1 || (arguments.size() == 1 && !PLAYER_NAME.matcher(arguments.getFirst()).matches())) {
                    throw unsupported();
                }
                Component head = AdventureBridges.current().head(arguments.isEmpty() ? null : arguments.getFirst());
                if (head == null) {
                    throw unsupported();
                }
                flush(pending, current);
                current.children.add(new Node.Insert(head));
            }
            default -> {
                if (this.standard.has(name)) {
                    throw unsupported();
                }
                // Discovering: a tag no standard or user tag claims is a <key> placeholder (as the stage 1 discovery parse
                // claims it), its slot added to the table.
                Integer slot = this.slots.name(TagKeys.key(name, tag.arguments(), this.modifiers), this.discover);
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
            if (name.equals("click") && !CLICK_ACTIONS.contains(arguments.getFirst().toLowerCase(Locale.ROOT))) {
                throw unsupported();
            }
            return new Element(name, Style.empty(), name.equals("click") ? arguments.getFirst() : "insert", argument, null);
        }
        if (hasInline(arguments)) {
            if (!slotsAllowed) {
                throw unsupported(); // in an argument of an argument: MiniMessage's
            }
            if (name.equals("hover") && arguments.size() == 2 && arguments.getFirst().equals("show_text")) {
                return new Element(name, this.inlineText(arguments.get(1), tag.before().get(1), false));
            }
            int from = switch (name) {
                case "lang", "tr", "translate" -> 1;
                case "lang_or", "tr_or", "translate_or" -> 2;
                default -> throw unsupported();
            };
            if (arguments.size() <= from || hasInline(arguments.subList(0, from))) {
                throw unsupported();
            }
            List<Object> with = new ArrayList<>(arguments.size() - from);
            for (int i = from; i < arguments.size(); i++) {
                String argument = arguments.get(i);
                with.add(argument.indexOf(Engine.INLINE_START) >= 0
                        ? this.inlineText(argument, tag.before().get(i), true)
                        : this.parse(argument, false).build().compact());
            }
            return new Element(name, new DynamicTranslatable(arguments.getFirst(), from == 2 ? arguments.get(1) : null, List.copyOf(with)));
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
     * Splits an argument at its inline markers. Legacy codes are not read in a click or insert tag, so a value is
     * written in as it is.
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
            if (start > from) {
                pieces.add(text.substring(from, start));
            }
            int index = Engine.parseIndex(text, start + 1, end);
            if (index < 0) {
                throw unsupported();
            }
            pieces.add(new Inline(index));
            from = end + 1;
        }
        if (from < text.length()) {
            pieces.add(text.substring(from));
        }
        return new Argument(List.copyOf(pieces), before != ':');
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
            if (arguments.size() != 1) {
                throw unsupported();
            }
            // As DecorationTag (4.17 to 5.2): "false" and "off" turn it off, any other argument turns it on.
            String argument = arguments.getFirst();
            return argument.equals("false") || argument.equals("off")
                    ? Style.empty().decoration(decoration, false)
                    : Style.style(decoration);
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
                // The action is read with its case, as HoverTag does.
                if (arguments.isEmpty()) {
                    throw unsupported();
                }
                yield switch (arguments.getFirst()) {
                    case "show_text" -> {
                        if (arguments.size() != 2) {
                            throw unsupported();
                        }
                        yield Style.style().hoverEvent(HoverEvent.showText(this.parse(arguments.get(1), false).build())).build();
                    }
                    case "show_item" -> Style.style().hoverEvent(showItem(arguments)).build();
                    case "show_entity" -> Style.style().hoverEvent(this.showEntity(arguments)).build();
                    default -> throw unsupported();
                };
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
        if (arguments.size() < 2) {
            throw unsupported();
        }
        // ClickTag reads the action in lower case.
        String action = arguments.getFirst().toLowerCase(Locale.ROOT);
        String value = arguments.get(1);
        if (action.equals("custom")) {
            if (arguments.size() > 3) {
                throw unsupported();
            }
            return required(AdventureBridges.current().clickCustom(value, arguments.size() == 3 ? arguments.get(2) : null));
        }
        if (arguments.size() != 2) {
            throw unsupported();
        }
        if (action.equals("change_page")) {
            return required(AdventureBridges.current().clickChangePage(value));
        }
        Style.Builder style = Style.style();
        try {
            switch (action) {
                case "run_command" -> style.clickEvent(ClickEvent.runCommand(value));
                case "suggest_command" -> style.clickEvent(ClickEvent.suggestCommand(value));
                case "open_url" -> style.clickEvent(ClickEvent.openUrl(value));
                case "copy_to_clipboard" -> style.clickEvent(ClickEvent.copyToClipboard(value));
                default -> throw unsupported(); // show_dialog: MiniMessage 5.2 refuses it itself
            }
        } catch (IllegalArgumentException e) {
            throw unsupported(); // Adventure 5.1+ refuses an invalid URL: MiniMessage decides what that gives
        }
        return style.build();
    }

    private static @NotNull Style required(@Nullable Style style) {
        if (style == null) {
            throw unsupported();
        }
        return style;
    }

    /**
     * {@code <hover:show_item:key[:count]>}, as HoverTag reads it (4.17 to 5.2). Item data components and legacy NBT
     * (more arguments) stay with MiniMessage.
     */
    private static @NotNull HoverEvent<?> showItem(@NotNull List<String> arguments) {
        if (arguments.size() < 2 || arguments.size() > 3) {
            throw unsupported();
        }
        try {
            net.kyori.adventure.key.Key key = net.kyori.adventure.key.Key.key(arguments.get(1));
            int count = arguments.size() == 3 ? Integer.parseInt(arguments.get(2)) : 1;
            return HoverEvent.showItem(key, count);
        } catch (RuntimeException e) { // an invalid key or count: MiniMessage reports it
            throw unsupported();
        }
    }

    /**
     * {@code <hover:show_entity:type:uuid[:name]>}, the name read as MiniMessage (natively here, without values).
     */
    private @NotNull HoverEvent<?> showEntity(@NotNull List<String> arguments) {
        if (arguments.size() < 3 || arguments.size() > 4) {
            throw unsupported();
        }
        net.kyori.adventure.key.Key type;
        java.util.UUID id;
        try {
            type = net.kyori.adventure.key.Key.key(arguments.get(1));
            id = java.util.UUID.fromString(arguments.get(2));
        } catch (RuntimeException e) {
            throw unsupported();
        }
        return arguments.size() == 4
                ? HoverEvent.showEntity(type, id, this.parse(arguments.get(3), false).build().compact())
                : HoverEvent.showEntity(type, id);
    }

    /**
     * Click actions whose value may hold inline values (a click with values in its argument).
     */
    private static final Set<String> CLICK_ACTIONS = Set.of("run_command", "suggest_command", "open_url", "copy_to_clipboard", "change_page");

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
