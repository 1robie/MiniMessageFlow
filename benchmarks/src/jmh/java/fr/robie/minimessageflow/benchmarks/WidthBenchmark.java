package fr.robie.minimessageflow.benchmarks;

import fr.robie.minimessageflow.MiniMessageFlow;
import fr.robie.minimessageflow.Placeholder;
import fr.robie.minimessageflow.Template;
import net.kyori.adventure.text.Component;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

import java.util.concurrent.TimeUnit;

/**
 * How a render grows with the number of placeholders: 1, 5 and 20 values, each under a colour of its own, so the
 * cost per value shows (the slope) apart from the cost per render (the intercept).
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class WidthBenchmark {

    private static final String[] COLORS = {"green", "gold", "aqua", "red", "yellow"};

    @Param({"1", "5", "20"})
    public int width;

    private Template template;
    private Placeholder built;
    private String[] keys;
    private String[] values;

    @Setup
    public void setup() {
        StringBuilder message = new StringBuilder();
        this.keys = new String[this.width];
        this.values = new String[this.width];
        for (int i = 0; i < this.width; i++) {
            this.keys[i] = "v" + i;
            this.values[i] = "value" + i;
            String color = COLORS[i % COLORS.length];
            message.append('<').append(color).append(">%").append(this.keys[i]).append("%</").append(color).append("> ");
        }
        this.template = MiniMessageFlow.create().compile(message.toString());
        this.built = this.build();
        System.out.println("[kind] width " + this.width + ": " + this.template.kind());
    }

    private Placeholder build() {
        Placeholder.Builder builder = Placeholder.builder();
        for (int i = 0; i < this.keys.length; i++) {
            builder.register(this.keys[i], this.values[i]);
        }
        return builder.build();
    }

    @Benchmark
    public Component render() {
        return this.template.render(this.built);
    }

    @Benchmark
    public Component buildAndRender() {
        return this.template.render(this.build());
    }
}
