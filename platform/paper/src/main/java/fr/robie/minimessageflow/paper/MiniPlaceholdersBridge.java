package fr.robie.minimessageflow.paper;

import fr.robie.minimessageflow.MiniMessageFlow;
import fr.robie.minimessageflow.ModifierSet;
import fr.robie.minimessageflow.Resolver;
import fr.robie.minimessageflow.core.AdventureBridges;
import fr.robie.minimessageflow.core.BoundedCache;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.pointer.Pointered;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * MiniPlaceholders in MiniMessageFlow, when it is installed (2.x or 3.x): its placeholders are values, as they are
 * written for it ({@code <player_name>}, {@code <server_online>}) and as {@code %player_name%}; modifiers apply
 * ({@code <player_name:upper>}), the audience is the render's viewer, and their components keep their style.
 * <pre>{@code
 * MiniPlaceholdersBridge.applyTo(flowBuilder);   // does nothing without MiniPlaceholders
 * }</pre>
 * Nothing of MiniPlaceholders is compiled in: its API is found when the server runs, so any version from 2.0 works.
 * Each placeholder is resolved by MiniPlaceholders at each render. On Adventure 4 (Paper 1.20.6 to 1.21) its tag
 * resolver is asked directly, as MiniMessage asks it while parsing; on Adventure 5 (Paper 26), whose parsing context
 * only MiniMessage can make, and for a tag that is not a component to insert, the tag alone is parsed: slower.
 */
public final class MiniPlaceholdersBridge {

    private static final String API = "io.github.miniplaceholders.api.MiniPlaceholders";
    /**
     * Parses a tag alone with MiniPlaceholders' resolver: no standard tag, so only its tags are read.
     */
    private static final MiniMessage TAGS = MiniMessage.builder().tags(TagResolver.empty()).build();

    private MiniPlaceholdersBridge() {
    }

    /**
     * Whether MiniPlaceholders is installed and enabled.
     */
    public static boolean isInstalled() {
        return Bukkit.getPluginManager().isPluginEnabled("MiniPlaceholders");
    }

    /**
     * Adds MiniPlaceholders to a builder when it is installed and enabled; does nothing otherwise.
     *
     * @return whether MiniPlaceholders was added
     */
    public static boolean applyTo(@NotNull MiniMessageFlow.Builder flow) {
        Objects.requireNonNull(flow, "flow cannot be null");
        if (!isInstalled()) {
            return false;
        }
        Function<Pointered, TagResolver> source = source(API);
        if (source == null) {
            return false;
        }
        flow.resolver(new Placeholders(source));
        return true;
    }

    /**
     * The placeholders of a viewer, through the API there is: {@code audienceGlobalPlaceholders()} (3.x, the viewer
     * being the parse's target), else {@code getAudienceGlobalPlaceholders(audience)} (2.x); null when neither is.
     */
    static @Nullable Function<@Nullable Pointered, TagResolver> source(@NotNull String apiClass) {
        Class<?> api;
        try {
            api = Class.forName(apiClass);
        } catch (ClassNotFoundException e) {
            return null;
        }
        MethodHandles.Lookup lookup = MethodHandles.publicLookup();
        MethodHandle three = find(lookup, api, "audienceGlobalPlaceholders", MethodType.methodType(TagResolver.class));
        if (three != null) {
            return viewer -> call(three, null);
        }
        MethodHandle twoAudience = find(lookup, api, "getAudienceGlobalPlaceholders",
                MethodType.methodType(TagResolver.class, Audience.class));
        MethodHandle twoGlobal = find(lookup, api, "getGlobalPlaceholders", MethodType.methodType(TagResolver.class));
        if (twoAudience != null && twoGlobal != null) {
            return viewer -> viewer instanceof Audience audience ? call(twoAudience, audience) : call(twoGlobal, null);
        }
        return null;
    }

    private static @Nullable MethodHandle find(@NotNull MethodHandles.Lookup lookup, @NotNull Class<?> owner,
                                               @NotNull String name, @NotNull MethodType type) {
        try {
            return lookup.findStatic(owner, name, type);
        } catch (NoSuchMethodException | IllegalAccessException e) {
            return null;
        }
    }

    private static @NotNull TagResolver call(@NotNull MethodHandle method, @Nullable Audience audience) {
        try {
            return (TagResolver) (audience == null ? method.invoke() : method.invoke(audience));
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * MiniPlaceholders as a resolver. Its resolver is asked again at each render: an expansion registered after this
     * plugin started is found too.
     */
    static final class Placeholders implements Resolver {
        private static final Tag NONE = new Tag("", "", List.of());

        /**
         * A key read as a tag: its name, its arguments, and the tag to parse when it has to be
         * ({@code <server_online:'world'>}).
         */
        private record Tag(@NotNull String name, @NotNull String text, @NotNull List<String> arguments) {
        }

        private final Function<@Nullable Pointered, TagResolver> source;
        private final BoundedCache<String, Tag> tags = new BoundedCache<>(256);

        Placeholders(@NotNull Function<@Nullable Pointered, TagResolver> source) {
            this.source = source;
        }

        @Override
        public @Nullable String resolve(@NotNull String key, @Nullable Pointered viewer) {
            return null; // every value is a component
        }

        @Override
        public @Nullable Component resolveComponent(@NotNull String key, @Nullable Pointered viewer) {
            Tag tag = this.tags.get(key, Placeholders::read);
            if (tag == NONE) {
                return null;
            }
            TagResolver resolver = this.source.apply(viewer);
            if (!resolver.has(tag.name)) {
                return null;
            }
            // Asked directly, as MiniMessage asks it, where Adventure lets it (4.x): a placeholder's tag inserts a
            // component, taken as it is. Else the tag alone is parsed.
            Component inserted = AdventureBridges.current().insertedTag(resolver, tag.name, tag.arguments, viewer);
            if (inserted != null) {
                return inserted;
            }
            return viewer == null ? TAGS.deserialize(tag.text, resolver) : TAGS.deserialize(tag.text, viewer, resolver);
        }

        /**
         * The tag a key reads ({@code name} or {@code name:a:b}, its arguments quoted), or {@link #NONE} for a key
         * with modifiers ({@code player_name.upper}: the engine asks again without them, then applies them) or a
         * name MiniMessage can't have.
         */
        private static @NotNull Tag read(@NotNull String key) {
            int end = 0;
            while (end < key.length() && key.charAt(end) != ':' && key.charAt(end) != '.') {
                char c = key.charAt(end);
                if (!(c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '_' || c == '-')) {
                    return NONE;
                }
                end++;
            }
            if (end == 0) {
                return NONE;
            }
            for (int dot = key.indexOf('.'); dot >= 0; dot = key.indexOf('.', dot + 1)) {
                if (ModifierSet.shared().compile(key.substring(dot + 1)) != null) {
                    return NONE;
                }
            }
            String name = key.substring(0, end);
            if (end == key.length()) {
                return new Tag(name, "<" + name + ">", List.of());
            }
            if (key.charAt(end) != ':') {
                return NONE;
            }
            List<String> arguments = List.of(key.substring(end + 1).split(":", -1));
            StringBuilder text = new StringBuilder("<").append(name);
            for (String argument : arguments) {
                text.append(":'").append(argument.replace("\\", "\\\\").replace("'", "\\'")).append('\'');
            }
            return new Tag(name, text.append('>').toString(), arguments);
        }
    }
}
