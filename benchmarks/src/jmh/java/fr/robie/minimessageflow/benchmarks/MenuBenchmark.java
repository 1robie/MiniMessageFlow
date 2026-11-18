package fr.robie.minimessageflow.benchmarks;

import fr.robie.minimessageflow.MiniMessageFlow;
import fr.robie.minimessageflow.Placeholder;
import fr.robie.minimessageflow.Template;
import fr.robie.minimessageflow.TextTemplate;
import fr.robie.minimessageflow.ValueKey;
import fr.robie.minimessageflow.Values;
import net.kyori.adventure.text.Component;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * A menu refreshed for many players, as a menu plugin does it: each player's values kept between refreshes, a line
 * drawn again with nothing changed. 1.4.0's layers, formatted values, remembered renders, text templates and lines.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class MenuBenchmark {

    private static final int VIEWERS = 50;

    private final MiniMessageFlow flow;
    private final ValueKey page;
    private final ValueKey name;
    private final ValueKey coins;
    private final ValueKey rank;
    private final ValueKey desc;
    private final Template line;
    private final Template lore;
    private final Template plain;
    private final TextTemplate command;
    private final Values[] shared = new Values[VIEWERS];
    private final Values[] remembered = new Values[VIEWERS];
    private final Values multiLine;
    private final Values oneLine;
    private int next;

    public MenuBenchmark() {
        MiniMessageFlow.Builder builder = MiniMessageFlow.builder();
        this.page = builder.key("page");
        this.name = builder.key("name");
        this.coins = builder.key("coins");
        this.rank = builder.key("rank");
        this.desc = builder.key("desc");
        this.flow = builder.build();
        this.line = this.flow.compile("<gray>%rank% <white>%name% <gray>- <gold>%coins% coins <dark_gray>(page %page%) "
                + "<click:run_command:'/pay %name%'>[pay]");
        this.lore = this.flow.compile("<gray>Info: <yellow>%desc%");
        this.plain = this.flow.compile("<white>%name%");
        this.command = this.flow.compileText("/pay %name% %coins% confirm");
        Values menu = this.flow.newValues().set(this.page, 1L);
        for (int i = 0; i < VIEWERS; i++) {
            this.shared[i] = this.viewer(menu.child(), i);
            this.remembered[i] = this.viewer(menu.child().rememberRenders(true), i);
        }
        this.multiLine = this.flow.newValues().set(this.desc, "first line\nsecond line\nthird line");
        this.oneLine = this.flow.newValues().set(this.desc, "one line only");
    }

    private Values viewer(Values values, int i) {
        return values.set(this.name, "Player" + i).set(this.coins, 1000L + i).setFormatted(this.rank, i % 2 == 0 ? "&c[Admin]" : "&a[VIP]");
    }

    private int nextViewer() {
        int i = this.next;
        this.next = i + 1 == VIEWERS ? 0 : i + 1;
        return i;
    }

    /**
     * 50 players in turn, values unchanged, the template's own memory (shared by every viewer).
     */
    @Benchmark
    public Component refreshSharedMemory() {
        return this.line.render(this.shared[this.nextViewer()], null);
    }

    /**
     * The same, each player's values remembering their renders.
     */
    @Benchmark
    public Component refreshRemembered() {
        return this.line.render(this.remembered[this.nextViewer()], null);
    }

    /**
     * One value read through a child layer: the menu's page under a slot's values.
     */
    @Benchmark
    public Component layeredOneValue() {
        return this.plain.render(this.shared[this.nextViewer()], null);
    }

    /**
     * A command with two values, as a String.
     */
    @Benchmark
    public String textTemplate() {
        return this.command.render(this.shared[this.nextViewer()], null);
    }

    /**
     * The same command with Placeholder.parse, for comparison.
     */
    @Benchmark
    public String placeholderParse() {
        int i = this.nextViewer();
        return Placeholder.of("name", "Player" + i, "coins", Long.toString(1000L + i)).parse("/pay %name% %coins% confirm");
    }

    @Benchmark
    public List<Component> renderLinesOne() {
        return this.lore.renderLines(this.oneLine, null);
    }

    @Benchmark
    public List<Component> renderLinesThree() {
        return this.lore.renderLines(this.multiLine, null);
    }
}
