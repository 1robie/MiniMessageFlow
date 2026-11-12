package fr.robie.minimessageflow.benchmarks;

import fr.robie.minimessageflow.MiniMessageFlow;
import fr.robie.minimessageflow.Placeholder;
import fr.robie.minimessageflow.Template;
import fr.robie.minimessageflow.ValueKey;
import fr.robie.minimessageflow.Values;
import net.kyori.adventure.text.Component;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;

import java.util.concurrent.TimeUnit;

/**
 * What a send costs when the values are built for it, as a plugin does: {@link TemplateBenchmark} renders with a
 * {@link Placeholder} built once, which leaves that part out. Each case here has the three numbers that tell them
 * apart: the render alone, the building alone, and both.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class ValuesBenchmark {

    private final MiniMessageFlow flow = MiniMessageFlow.create();

    private final Template one = this.flow.compile("<gray>Hello <white>%name%");
    private final Template two = this.flow.compile("<green>%item%</green> for <gold>%price%</gold>");
    private final Template three = this.flow.compile("<green>%item%</green> for <gold>%price%</gold> <click:run_command:'/buy 42'>[Buy %n%]");

    private final Placeholder oneBuilt = Placeholder.of("name", "Steve");
    private final Placeholder twoBuilt = Placeholder.of("item", "Sword", "price", "1,250");
    private final Placeholder threeBuilt = threeValues();

    private static Placeholder threeValues() {
        return Placeholder.builder()
                .register("item", "Sword")
                .register("price", "1,250")
                .register("n", "1")
                .build();
    }

    @Benchmark
    public Component oneRender() {
        return this.one.render(this.oneBuilt);
    }

    @Benchmark
    public Placeholder oneBuild() {
        return Placeholder.of("name", "Steve");
    }

    @Benchmark
    public Component oneBuildAndRender() {
        return this.one.render(Placeholder.of("name", "Steve"));
    }

    @Benchmark
    public Component twoRender() {
        return this.two.render(this.twoBuilt);
    }

    @Benchmark
    public Placeholder twoBuild() {
        return Placeholder.of("item", "Sword", "price", "1,250");
    }

    @Benchmark
    public Component twoBuildAndRender() {
        return this.two.render(Placeholder.of("item", "Sword", "price", "1,250"));
    }

    @Benchmark
    public Component threeRender() {
        return this.three.render(this.threeBuilt);
    }

    @Benchmark
    public Placeholder threeBuild() {
        return threeValues();
    }

    @Benchmark
    public Component threeBuildAndRender() {
        return this.three.render(threeValues());
    }

    // The same three values through Values: keys declared once, one array set again for each send.

    private final MiniMessageFlow.Builder keyed = MiniMessageFlow.builder();
    private final ValueKey item = this.keyed.key("item");
    private final ValueKey price = this.keyed.key("price");
    private final ValueKey n = this.keyed.key("n");
    private final MiniMessageFlow keyedFlow = this.keyed.build();
    private final Template keyedThree = this.keyedFlow.compile("<green>%item%</green> for <gold>%price%</gold> <click:run_command:'/buy 42'>[Buy %n%]");
    private final Values values = this.keyedFlow.newValues().set(this.item, "Sword").set(this.price, "1,250").set(this.n, "1");

    @Benchmark
    public Component threeValuesRender() {
        return this.keyedThree.render(this.values);
    }

    @Benchmark
    public Component threeValuesSetAndRender() {
        this.values.set(this.item, "Sword").set(this.price, "1,250").set(this.n, "1");
        return this.keyedThree.render(this.values);
    }
}
