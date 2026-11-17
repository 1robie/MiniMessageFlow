package fr.robie.minimessageflow.paper;

import fr.robie.minimessageflow.MiniMessageFlow;
import fr.robie.minimessageflow.Placeholder;
import fr.robie.minimessageflow.Template;
import net.kyori.adventure.text.Component;
import org.bukkit.OfflinePlayer;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * What platform/paper adds to a render, a new player at each one: the built-in placeholders and PlaceholderAPI (a
 * fake one, answering at once), each beside the same message with a plain resolver giving the same values. The
 * players and the server are dynamic proxies, so a call to them costs more than on a real server: compare each pair,
 * not the absolute numbers. In the module's package, to reach its test hooks (no Bukkit server here).
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class PaperBenchmark {

    private static final int PLAYERS = 16;

    private final Player[] players = new Player[PLAYERS];
    private final Server server = (Server) Proxy.newProxyInstance(Server.class.getClassLoader(), new Class<?>[]{Server.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getOnlinePlayers" -> List.of();
                default -> throw new UnsupportedOperationException(method.getName());
            });
    private int counter;

    private static final class Api implements PlaceholderApi.Hook {
        @Override
        public boolean isRegistered(@NotNull String identifier) {
            return identifier.equals("vault");
        }

        @Override
        public @NotNull String setPlaceholders(@Nullable OfflinePlayer player, @NotNull String text) {
            return text.equals("%vault_eco_balance%") && player != null ? player.getName() : text;
        }
    }

    private final Template builtIns;
    private final Template builtInsResolver;
    private final Template papiTag;
    private final Template papiBare;
    private final Template papiResolver;
    private final Template miniPlaceholders;
    private final Template miniPlaceholdersAsResolver;

    public PaperBenchmark() {
        for (int i = 0; i < PLAYERS; i++) {
            String name = String.valueOf(1000 + i);
            this.players[i] = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getName" -> name;
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
        }
        MiniMessageFlow.Builder paper = MiniMessageFlow.builder();
        PaperPlaceholders.builder().applyTo(paper, () -> this.server);
        PlaceholderApi.builder().applyTo(paper, new Api(), player -> true, Logger.getLogger("bench"));
        MiniMessageFlow paperFlow = paper.build();
        MiniMessageFlow plain = MiniMessageFlow.builder()
                .resolver((key, viewer) -> switch (key) {
                    case "name", "balance" -> viewer instanceof Player player ? player.getName() : null;
                    case "online" -> String.valueOf(this.server.getOnlinePlayers().size());
                    default -> null;
                })
                .build();
        this.builtIns = paperFlow.compile("<gray>Hi <white><viewer_name></white>, <server_online> online");
        this.builtInsResolver = plain.compile("<gray>Hi <white><name></white>, <online> online");
        this.papiTag = paperFlow.compile("<gray>Balance: <gold><papi:vault_eco_balance:comma>");
        this.papiBare = paperFlow.compile("<gray>Balance: <gold>%vault_eco_balance.comma%");
        this.papiResolver = plain.compile("<gray>Balance: <gold>%balance.comma%");
        // MiniPlaceholders 3.x's way: a resolver whose tag reads the parse's target.
        net.kyori.adventure.text.minimessage.tag.resolver.TagResolver tags = net.kyori.adventure.text.minimessage.tag.resolver.TagResolver.resolver(
                "player_name", (arguments, context) -> net.kyori.adventure.text.minimessage.tag.Tag.selfClosingInserting(
                        Component.text(((Player) context.target()).getName())));
        MiniMessageFlow mini = MiniMessageFlow.builder().resolver(new MiniPlaceholdersBridge.Placeholders(viewer -> tags)).build();
        this.miniPlaceholders = mini.compile("<gray>Hi <white><player_name>");
        this.miniPlaceholdersAsResolver = plain.compile("<gray>Hi <white><name>");
    }

    private Player next() {
        return this.players[this.counter++ & (PLAYERS - 1)];
    }

    @Benchmark
    public Component builtIns() {
        return this.builtIns.render(Placeholder.empty(), this.next());
    }

    @Benchmark
    public Component builtInsAsResolver() {
        return this.builtInsResolver.render(Placeholder.empty(), this.next());
    }

    @Benchmark
    public Component papiTag() {
        return this.papiTag.render(Placeholder.empty(), this.next());
    }

    @Benchmark
    public Component papiBare() {
        return this.papiBare.render(Placeholder.empty(), this.next());
    }

    @Benchmark
    public Component miniPlaceholders() {
        return this.miniPlaceholders.render(Placeholder.empty(), this.next());
    }

    @Benchmark
    public Component miniPlaceholdersAsResolver() {
        return this.miniPlaceholdersAsResolver.render(Placeholder.empty(), this.next());
    }

    @Benchmark
    public Component papiAsResolver() {
        return this.papiResolver.render(Placeholder.empty(), this.next());
    }
}
