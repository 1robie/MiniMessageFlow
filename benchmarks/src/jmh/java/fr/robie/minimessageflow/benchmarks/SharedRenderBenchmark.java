package fr.robie.minimessageflow.benchmarks;

import fr.robie.minimessageflow.MiniMessageFlow;
import fr.robie.minimessageflow.Placeholder;
import fr.robie.minimessageflow.Template;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;

import java.util.concurrent.TimeUnit;

/**
 * {@link RenderBenchmark}'s renders on templates every thread shares, as a server's threads do: run it with
 * {@code -Pjmh.threads=8} and compare with one thread. What a render writes in its template (the last render, a
 * component value's styling) is then read and written by every thread. Each thread takes the {@link #SETS} sets of
 * values from its own place, far from the others': values seldom come again, as when every send has its own.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class SharedRenderBenchmark {

    private static final int SETS = 256;

    private final MiniMessageFlow flow = MiniMessageFlow.create();
    private final Template joined = this.flow.compile("<green>Welcome %name%!");
    private final Template three = this.flow.compile("<green>%item%</green> for <gold>%price%</gold> <click:run_command:'/buy 42'>[Buy %n%]");
    private final Template five = this.flow.compile("<red>%v0% <gold>%v1% <green>%v2% <aqua>%v3% <blue>%v4%");
    private final Template hover = this.flow.compile("<hover:show_text:'<gray>Balance: <gold>%price%'>%name%</hover> joined");
    private final Template gradient = this.flow.compile("<gradient:red:blue>Welcome %name%!</gradient>");
    /**
     * A component value under a colour: its styled copy is remembered in the template.
     */
    private final Template component = this.flow.compile("<red>%item% <gray>x%n%");

    private final Placeholder[] sets = new Placeholder[SETS];
    /**
     * The same values every time: the last render given again, read by every thread.
     */
    private final Placeholder fixed;

    public SharedRenderBenchmark() {
        for (int s = 0; s < SETS; s++) {
            Placeholder.Builder builder = Placeholder.builder()
                    .register("name", "Steve" + s)
                    .register("price", Integer.toString(1000 + s))
                    .register("n", Integer.toString(s + 1))
                    .register("item", Component.text("Sword" + s, NamedTextColor.AQUA));
            for (int i = 0; i < 5; i++) {
                builder.register("v" + i, "value" + i + "-" + s);
            }
            this.sets[s] = builder.build();
        }
        this.fixed = this.sets[0];
    }

    /**
     * Each thread's place in the sets, started at a different one.
     */
    @State(Scope.Thread)
    public static class Cursor {
        private static final java.util.concurrent.atomic.AtomicInteger THREADS = new java.util.concurrent.atomic.AtomicInteger();
        int counter = THREADS.getAndIncrement() * 32;

        int next() {
            return this.counter++ & (SETS - 1);
        }
    }

    @Benchmark
    public Component joined(Cursor cursor) {
        return this.joined.render(this.sets[cursor.next()]);
    }

    @Benchmark
    public Component three(Cursor cursor) {
        return this.three.render(this.sets[cursor.next()]);
    }

    @Benchmark
    public Component threeSame() {
        return this.three.render(this.fixed);
    }

    @Benchmark
    public Component five(Cursor cursor) {
        return this.five.render(this.sets[cursor.next()]);
    }

    @Benchmark
    public Component hover(Cursor cursor) {
        return this.hover.render(this.sets[cursor.next()]);
    }

    @Benchmark
    public Component gradient(Cursor cursor) {
        return this.gradient.render(this.sets[cursor.next()]);
    }

    @Benchmark
    public Component componentValue(Cursor cursor) {
        return this.component.render(this.sets[cursor.next()]);
    }

    @Benchmark
    public Component componentValueSame() {
        return this.component.render(this.fixed);
    }
}
