package fr.robie.minimessageflow.benchmarks;

import fr.robie.minimessageflow.MiniMessageFlow;
import fr.robie.minimessageflow.Placeholder;
import fr.robie.minimessageflow.Resolver;
import fr.robie.minimessageflow.Template;
import net.kyori.adventure.pointer.Pointered;
import net.kyori.adventure.text.Component;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

import java.util.concurrent.TimeUnit;

/**
 * The renders that leave the fast path, which {@link TemplateBenchmark} does not show: a value in a hover's text or
 * in a translation argument, a {@code <name>} without a value, and resolvers that have nothing for a key.
 * "Changing" cases give a value among 1024, as a server with that many players would.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class PathsBenchmark {

    /**
     * A resolver that knows no key: what a hook costs for the keys it does not have.
     */
    private static final Resolver KNOWS_NOTHING = new Resolver() {
        @Override
        public @Nullable String resolve(@NotNull String key, @Nullable Pointered viewer) {
            return null;
        }
    };

    private final MiniMessageFlow flow = MiniMessageFlow.create();
    private final MiniMessageFlow withResolvers = MiniMessageFlow.builder()
            .resolver(KNOWS_NOTHING)
            .resolver(KNOWS_NOTHING)
            .resolver(Resolver.of(Placeholder.of("server", "Lobby")))
            .build();

    private final Template hover = this.flow.compile("<hover:show_text:'<gray>Balance: <gold>%coins%'>%name%</hover> joined");
    private final Template langArgument = this.flow.compile("<lang:chat.type.text:'%name%':'<green>%message%'>");
    private final Template names = this.flow.compile("<green><item></green> for <gold><price></gold>");
    private final Template percents = this.flow.compile("<green>%item%</green> for <gold>%price%</gold>");
    private final Template global = this.withResolvers.compile("<gray>[%server%] <white>%name%");
    private final Template globalUnknown = this.withResolvers.compile("<gray>[%nobody%] <white>%name%");

    private final Placeholder hoverValues = Placeholder.of("name", "Steve", "coins", "1,250");
    private final Placeholder langValues = Placeholder.of("name", "Steve", "message", "hello");
    private final Placeholder itemAndPrice = Placeholder.of("item", "Sword", "price", "1,250");
    private final Placeholder itemOnly = Placeholder.of("item", "Sword");
    private final Placeholder name = Placeholder.of("name", "Steve");

    private int counter;

    @Setup
    public void kinds() {
        System.out.println("[kind] hover: " + this.hover.kind() + ", lang argument: " + this.langArgument.kind()
                + ", names: " + this.names.kind() + ", percents: " + this.percents.kind()
                + ", global: " + this.global.kind());
    }

    @Benchmark
    public Component hoverValueSame() {
        return this.hover.render(this.hoverValues);
    }

    @Benchmark
    public Component hoverValueChanging() {
        return this.hover.render(Placeholder.of("name", "P" + (this.counter++ & 1023), "coins", "1,250"));
    }

    /**
     * The value inside the hover's text changes: the text MiniMessage parses is another one each time.
     */
    @Benchmark
    public Component hoverTextChanging() {
        return this.hover.render(Placeholder.of("name", "Steve", "coins", String.valueOf(this.counter++ & 1023)));
    }

    @Benchmark
    public Component langArgumentSame() {
        return this.langArgument.render(this.langValues);
    }

    @Benchmark
    public Component langArgumentChanging() {
        return this.langArgument.render(Placeholder.of("name", "P" + (this.counter++ & 1023), "message", "hello"));
    }

    @Benchmark
    public Component namesAllSet() {
        return this.names.render(this.itemAndPrice);
    }

    @Benchmark
    public Component namesOneUnset() {
        return this.names.render(this.itemOnly);
    }

    @Benchmark
    public Component percentsOneUnset() {
        return this.percents.render(this.itemOnly);
    }

    @Benchmark
    public Component resolverThirdHasIt() {
        return this.global.render(this.name);
    }

    @Benchmark
    public Component resolverNoneHasIt() {
        return this.globalUnknown.render(this.name);
    }
}
