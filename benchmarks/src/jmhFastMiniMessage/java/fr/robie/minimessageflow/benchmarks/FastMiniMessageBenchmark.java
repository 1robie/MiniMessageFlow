package fr.robie.minimessageflow.benchmarks;

import fr.robie.minimessageflow.MiniMessageFlow;
import fr.robie.minimessageflow.Placeholder;
import fr.robie.minimessageflow.Template;
import io.github.neoramm.fastminimessage.v2.MiniMessageCompiler;
import io.github.neoramm.fastminimessage.v2.ResolverRegistry;
import io.github.neoramm.fastminimessage.v2.model.PayloadSchema;
import io.github.neoramm.fastminimessage.v2.model.PreComponentTree;
import io.github.neoramm.fastminimessage.v2.model.RenderScope;
import net.kyori.adventure.text.Component;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;

import java.util.concurrent.TimeUnit;

/**
 * The same messages on FastMiniMessage (v2) and here. Only compiled with {@code -PfastMiniMessageVersion}, and only
 * runs on Adventure 5.2+.
 * <p>
 * The two are not fed the same way, and the numbers must be read with that in mind: FastMiniMessage takes
 * components the caller built, through typed keys, in a scope that is reused; here the values are text in a
 * {@link Placeholder} built once ("flow...") or for each send ("flow...Built"). A scope is not thread-safe, hence
 * {@link Scope#Thread}.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class FastMiniMessageBenchmark {

    private static final String STATIC = "<green>Welcome to the server!";

    private final MiniMessageFlow flow = MiniMessageFlow.create();
    private final Template flowStatic = this.flow.compile(STATIC);
    private final Template flowThree = this.flow.compile("<green>%item%</green> for <gold>%price%</gold> <click:run_command:'/buy 42'>[Buy %n%]");
    private final Template flowGradient = this.flow.compile("<gradient:red:blue>Welcome %name%!</gradient>");
    private final Placeholder flowThreeValues = threeValues();
    private final Placeholder flowName = Placeholder.of("name", "Steve");

    private final PayloadSchema.Key<Component> item;
    private final PayloadSchema.Key<Component> price;
    private final PayloadSchema.Key<Component> n;
    private final PayloadSchema.Key<Component> name;
    private final PreComponentTree fastStatic;
    private final PreComponentTree fastThree;
    private final PreComponentTree fastGradient;
    private final RenderScope.Mutable scope;

    public FastMiniMessageBenchmark() {
        PayloadSchema.Builder layout = PayloadSchema.builder();
        // Optional keys: one scope serves messages that use only some of them.
        this.item = layout.optionalKey("item", Component.class);
        this.price = layout.optionalKey("price", Component.class);
        this.n = layout.optionalKey("n", Component.class);
        this.name = layout.optionalKey("name", Component.class);
        PayloadSchema schema = layout.build();
        MiniMessageCompiler compiler = new MiniMessageCompiler(ResolverRegistry.withDefaults(schema)
                .registerPlaceholder("item", scope -> scope.payload(this.item))
                .registerPlaceholder("price", scope -> scope.payload(this.price))
                .registerPlaceholder("n", scope -> scope.payload(this.n))
                .registerPlaceholder("name", scope -> scope.payload(this.name)));
        this.fastStatic = compiler.compile(STATIC);
        this.fastThree = compiler.compile("<green><item></green> for <gold><price></gold> <click:run_command:'/buy 42'>[Buy <n>]");
        this.fastGradient = compiler.compile("<gradient:red:blue>Welcome <name>!</gradient>");
        this.scope = RenderScope.mutable(schema);
        this.scope.payload(this.item, Component.text("Sword"))
                .payload(this.price, Component.text("1,250"))
                .payload(this.n, Component.text("1"))
                .payload(this.name, Component.text("Steve"));
    }

    private static Placeholder threeValues() {
        return Placeholder.builder()
                .register("item", "Sword")
                .register("price", "1,250")
                .register("n", "1")
                .build();
    }

    @Benchmark
    public Component fastStatic() {
        return this.fastStatic.render();
    }

    @Benchmark
    public Component flowStatic() {
        return this.flowStatic.render();
    }

    /**
     * The scope is set once: the render alone.
     */
    @Benchmark
    public Component fastThree() {
        return this.fastThree.render(this.scope);
    }

    /**
     * The values are set for each send, components included, as a plugin would.
     */
    @Benchmark
    public Component fastThreeSet() {
        this.scope.payload(this.item, Component.text("Sword"))
                .payload(this.price, Component.text("1,250"))
                .payload(this.n, Component.text("1"));
        return this.fastThree.render(this.scope);
    }

    @Benchmark
    public Component flowThree() {
        return this.flowThree.render(this.flowThreeValues);
    }

    @Benchmark
    public Component flowThreeBuilt() {
        return this.flowThree.render(threeValues());
    }

    private final MiniMessageFlow.Builder keyed = MiniMessageFlow.builder();
    private final fr.robie.minimessageflow.ValueKey itemKey = this.keyed.key("item");
    private final fr.robie.minimessageflow.ValueKey priceKey = this.keyed.key("price");
    private final fr.robie.minimessageflow.ValueKey nKey = this.keyed.key("n");
    private final MiniMessageFlow keyedFlow = this.keyed.build();
    private final Template flowThreeKeyed = this.keyedFlow.compile("<green>%item%</green> for <gold>%price%</gold> <click:run_command:'/buy 42'>[Buy %n%]");
    private final fr.robie.minimessageflow.Values values = this.keyedFlow.newValues();

    /**
     * The values set for each send in a {@code Values} kept between sends: the counterpart of {@link #fastThreeSet}.
     */
    @Benchmark
    public Component flowThreeSet() {
        this.values.set(this.itemKey, "Sword").set(this.priceKey, "1,250").set(this.nKey, "1");
        return this.flowThreeKeyed.render(this.values);
    }

    @Benchmark
    public Component fastGradient() {
        return this.fastGradient.render(this.scope);
    }

    @Benchmark
    public Component flowGradient() {
        return this.flowGradient.render(this.flowName);
    }
}
