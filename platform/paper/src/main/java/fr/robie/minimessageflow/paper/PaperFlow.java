package fr.robie.minimessageflow.paper;

import fr.robie.minimessageflow.MiniMessageFlow;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * A {@link MiniMessageFlow} set up for a Paper plugin, in one call:
 * <pre>{@code
 * MiniMessageFlow flow = PaperFlow.builder(plugin)
 *         .configure(builder -> builder.numberStyle(NumberStyle.EUROPEAN))   // anything of MiniMessageFlow.Builder
 *         .build();
 * }</pre>
 * It has, each one can be turned off:
 * <ul>
 *   <li>PlaceholderAPI when it is installed ({@link PlaceholderApi}): {@code %vault_eco_balance%},
 *   {@code <papi:vault_eco_balance>};</li>
 *   <li>the viewer's and the server's placeholders ({@link PaperPlaceholders}): {@code %viewer_name%},
 *   {@code <server_online>};</li>
 *   <li>an error handler that logs to the plugin's logger, once per placeholder.</li>
 * </ul>
 * What {@link Builder#configure} adds comes first: your resolvers and value tags are asked before the built-in ones.
 */
public final class PaperFlow {

    private PaperFlow() {
    }

    public static @NotNull Builder builder(@NotNull Plugin plugin) {
        return new Builder(Objects.requireNonNull(plugin, "plugin cannot be null"));
    }

    public static final class Builder {
        private final Plugin plugin;
        private final MiniMessageFlow.Builder flow = MiniMessageFlow.builder();
        private @Nullable PlaceholderApi.Builder placeholderApi = PlaceholderApi.builder();
        private @Nullable PaperPlaceholders.Builder placeholders = PaperPlaceholders.builder();
        private boolean logErrors = true;

        private Builder(@NotNull Plugin plugin) {
            this.plugin = plugin;
        }

        /**
         * Sets up the instance itself: number style, resolvers, value tags, tags... Called at once, in order.
         */
        public @NotNull Builder configure(@NotNull Consumer<MiniMessageFlow.Builder> configuration) {
            Objects.requireNonNull(configuration, "configuration cannot be null").accept(this.flow);
            return this;
        }

        /**
         * Whether PlaceholderAPI is used when it is installed. True by default.
         */
        public @NotNull Builder placeholderApi(boolean enabled) {
            this.placeholderApi = enabled ? PlaceholderApi.builder() : null;
            return this;
        }

        /**
         * How PlaceholderAPI is used when it is installed: which keys, on which threads.
         */
        public @NotNull Builder placeholderApi(@NotNull PlaceholderApi.Builder placeholderApi) {
            this.placeholderApi = Objects.requireNonNull(placeholderApi, "placeholderApi cannot be null");
            return this;
        }

        /**
         * Whether the viewer's and the server's placeholders are added. True by default.
         */
        public @NotNull Builder placeholders(boolean enabled) {
            this.placeholders = enabled ? PaperPlaceholders.builder() : null;
            return this;
        }

        /**
         * Which viewer and server placeholders are added: the built-in ones changed, yours added.
         */
        public @NotNull Builder placeholders(@NotNull PaperPlaceholders.Builder placeholders) {
            this.placeholders = Objects.requireNonNull(placeholders, "placeholders cannot be null");
            return this;
        }

        /**
         * Whether a placeholder that fails (a resolver or a value that throws) is logged to the plugin's logger,
         * once per placeholder. True by default; false keeps MiniMessageFlow's own handler, or the one given to
         * {@code errorHandler} in {@link #configure}.
         */
        public @NotNull Builder logErrors(boolean logErrors) {
            this.logErrors = logErrors;
            return this;
        }

        public @NotNull MiniMessageFlow build() {
            if (this.logErrors) {
                Set<String> logged = ConcurrentHashMap.newKeySet();
                this.flow.errorHandler((key, error) -> {
                    if (logged.add(key)) {
                        this.plugin.getLogger().log(Level.WARNING, "placeholder %" + key + "% failed; shown as written"
                                + " (logged once)", error);
                    }
                });
            }
            if (this.placeholderApi != null) {
                this.placeholderApi.applyTo(this.flow, this.plugin);
            }
            if (this.placeholders != null) {
                this.placeholders.applyTo(this.flow);
            }
            return this.flow.build();
        }
    }
}
