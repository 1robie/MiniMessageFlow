package fr.robie.minimessageflow;

import net.kyori.adventure.identity.Identity;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.pointer.Pointered;
import net.kyori.adventure.pointer.Pointers;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TranslatableComponent;
import net.kyori.adventure.text.TranslationArgument;
import net.kyori.adventure.text.format.Style;
import net.kyori.adventure.translation.Translator;
import net.kyori.adventure.util.TriState;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.text.MessageFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Translations written in MiniMessageFlow, for Adventure's {@code GlobalTranslator}: a
 * {@code Component.translatable("shop.bought", item, price)} sent to a player renders the template registered for that
 * key in the player's language.
 * <pre>{@code
 * MiniMessageFlowTranslator translator = MiniMessageFlowTranslator.create(Key.key("myplugin", "messages"), flow);
 * translator.register("shop.bought", Locale.ENGLISH, "<green>You bought <arg:0> for <gold><arg:1></gold>");
 * translator.register("shop.bought", Locale.FRENCH, "<green>Tu as acheté %0% pour <gold>%1%</gold>");
 * GlobalTranslator.translator().addSource(translator);
 * }</pre>
 * <ul>
 *     <li>Argument {@code i} of the translatable is written {@code <arg:i>} or {@code <argument:i>} (as with
 *     MiniMessage's translator), or {@code %i%} / {@code <i>}. It is inserted as a component: its own style is kept.</li>
 *     <li>The language: the exact locale, then its language alone ({@code fr_CA} → {@code fr}), then the default
 *     locale ({@link #defaultLocale}); without any, the translatable is left to other sources.</li>
 *     <li>The viewer of a render is the locale (its {@link Identity#LOCALE} pointer), so a viewer value or resolver can
 *     read it. The translatable's style is applied where the template sets none, and its children are appended, as
 *     MiniMessage's translator does.</li>
 *     <li>Each template is compiled when it is registered. Thread-safe.</li>
 * </ul>
 */
public final class MiniMessageFlowTranslator implements Translator {

    private final Key name;
    private final MiniMessageFlow flow;
    private final Map<String, Map<Locale, Template>> templates = new ConcurrentHashMap<>();
    private volatile @Nullable Locale defaultLocale;

    private MiniMessageFlowTranslator(@NotNull Key name, @NotNull MiniMessageFlow flow) {
        this.name = name;
        this.flow = flow;
    }

    /**
     * @param name a name unique among the translator's sources ({@code myplugin:messages})
     * @param flow compiles the templates (its resolvers and tags apply)
     */
    public static @NotNull MiniMessageFlowTranslator create(@NotNull Key name, @NotNull MiniMessageFlow flow) {
        return new MiniMessageFlowTranslator(Objects.requireNonNull(name, "name"), Objects.requireNonNull(flow, "flow"));
    }

    /**
     * The locale used when neither a player's locale nor its language has a template. None by default.
     */
    public @NotNull MiniMessageFlowTranslator defaultLocale(@Nullable Locale locale) {
        this.defaultLocale = locale;
        return this;
    }

    /**
     * Registers (or replaces) the template of a key in a locale, compiled now.
     */
    public @NotNull MiniMessageFlowTranslator register(@NotNull String key, @NotNull Locale locale, @NotNull String message) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(locale, "locale");
        Template template = this.flow.compile(Objects.requireNonNull(message, "message"));
        this.templates.computeIfAbsent(key, k -> new ConcurrentHashMap<>()).put(locale, template);
        return this;
    }

    /**
     * Registers the templates of many keys in a locale.
     */
    public @NotNull MiniMessageFlowTranslator registerAll(@NotNull Locale locale, @NotNull Map<String, String> messages) {
        messages.forEach((key, message) -> this.register(key, locale, message));
        return this;
    }

    /**
     * Removes a key in every locale.
     */
    public void unregister(@NotNull String key) {
        this.templates.remove(key);
    }

    public boolean contains(@NotNull String key) {
        return this.templates.containsKey(key);
    }

    @Override
    public @NotNull Key name() {
        return this.name;
    }

    @Override
    public @NotNull TriState hasAnyTranslations() {
        return this.templates.isEmpty() ? TriState.FALSE : TriState.TRUE;
    }

    /**
     * Always null: templates render components, never a {@link MessageFormat}.
     */
    @Override
    public @Nullable MessageFormat translate(@NotNull String key, @NotNull Locale locale) {
        return null;
    }

    @Override
    public @Nullable Component translate(@NotNull TranslatableComponent component, @NotNull Locale locale) {
        return this.translate(component, locale, 0);
    }

    /**
     * How deep translatables of this translator inside a template are rendered here (a template that refers to itself
     * stops there).
     */
    private static final int MAX_DEPTH = 16;

    private @Nullable Component translate(@NotNull TranslatableComponent component, @NotNull Locale locale, int depth) {
        Map<Locale, Template> byLocale = this.templates.get(component.key());
        if (byLocale == null) {
            return null;
        }
        Template template = this.find(byLocale, locale);
        if (template == null) {
            return null;
        }
        Component result = template.render(arguments(component.arguments()), viewer(locale));
        Style style = component.style();
        if (!style.isEmpty()) {
            result = result.applyFallbackStyle(style);
        }
        for (Component child : component.children()) {
            result = result.append(child);
        }
        // Adventure 4.17's GlobalTranslator does not render the translatables a translator returns (later versions
        // do): those of this translator are rendered here, so a template can use another on every version.
        return depth < MAX_DEPTH ? this.renderOwn(result, locale, depth + 1) : result;
    }

    private @NotNull Component renderOwn(@NotNull Component component, @NotNull Locale locale, int depth) {
        if (component instanceof TranslatableComponent translatable && this.templates.containsKey(translatable.key())) {
            Component translated = this.translate(translatable, locale, depth);
            if (translated != null) {
                return translated;
            }
        }
        List<Component> children = component.children();
        List<Component> rendered = null;
        for (int i = 0; i < children.size(); i++) {
            Component child = children.get(i);
            Component renderedChild = this.renderOwn(child, locale, depth);
            if (renderedChild != child) {
                if (rendered == null) {
                    rendered = new java.util.ArrayList<>(children);
                }
                rendered.set(i, renderedChild);
            }
        }
        return rendered == null ? component : component.children(rendered);
    }

    private @Nullable Template find(@NotNull Map<Locale, Template> byLocale, @NotNull Locale locale) {
        Template template = byLocale.get(locale);
        if (template == null && !locale.getCountry().isEmpty()) {
            template = byLocale.get(Locale.of(locale.getLanguage()));
        }
        Locale fallback = this.defaultLocale;
        if (template == null && fallback != null) {
            template = byLocale.get(fallback);
        }
        return template;
    }

    /**
     * Argument {@code i} as {@code i} ({@code %i%}, {@code <i>}), {@code arg:i} and {@code argument:i}.
     */
    private static @NotNull Placeholder arguments(@NotNull List<TranslationArgument> arguments) {
        if (arguments.isEmpty()) {
            return Placeholder.empty();
        }
        Placeholder.Builder builder = Placeholder.builder();
        for (int i = 0; i < arguments.size(); i++) {
            Component value = arguments.get(i).asComponent();
            builder.register(String.valueOf(i), value);
            builder.register("arg:" + i, value);
            builder.register("argument:" + i, value);
        }
        return builder.build();
    }

    private static @NotNull Pointered viewer(@NotNull Locale locale) {
        Pointers pointers = Pointers.builder().withStatic(Identity.LOCALE, locale).build();
        return new Pointered() {
            @Override
            public @NotNull Pointers pointers() {
                return pointers;
            }
        };
    }
}
