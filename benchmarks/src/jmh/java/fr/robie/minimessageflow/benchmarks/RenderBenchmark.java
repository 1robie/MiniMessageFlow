package fr.robie.minimessageflow.benchmarks;

import fr.robie.minimessageflow.MiniMessageFlow;
import fr.robie.minimessageflow.Placeholder;
import fr.robie.minimessageflow.Template;
import fr.robie.minimessageflow.ValueKey;
import fr.robie.minimessageflow.Values;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

/**
 * What a render builds when the values change at each send. A template gives its last component again while the
 * values are the same, so {@link TemplateBenchmark} (one set of values) measures that shortcut; here each render
 * takes the next of {@link #SETS} sets of values, built before the run, so every render builds its component and
 * nothing else is timed.
 * <p>
 * {@code ...Send} also serialises the result to JSON, as a server does before sending it: a tree with fewer
 * components costs less there too.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class RenderBenchmark {

    private static final int SETS = 16;
    private static final GsonComponentSerializer GSON = GsonComponentSerializer.gson();

    private final MiniMessageFlow flow = MiniMessageFlow.create();

    private final Template one = this.flow.compile("<gray>Hello <white>%name%");
    private final Template joined = this.flow.compile("<green>Welcome %name%!");
    private final Template five = this.flow.compile(colored(5));
    private final Template twenty = this.flow.compile(colored(20));
    private final Template three = this.flow.compile("<green>%item%</green> for <gold>%price%</gold> <click:run_command:'/buy 42'>[Buy %n%]");
    private final Template shop = this.flow.compile("<green>%item%</green> for <gold>%price.comma%</gold> <click:run_command:'/buy 42'>[Buy]");
    private final Template clickValue = this.flow.compile("<click:run_command:'/tp %name%'>[tp %name%]");
    private final Template hover = this.flow.compile("<hover:show_text:'<gray>Balance: <gold>%price%'>%name%</hover> joined");
    private final Template langArgument = this.flow.compile("<lang:chat.type.text:'%name%':'<green>%item%'>");
    private final Template gradient = this.flow.compile("<gradient:red:blue>Welcome %name%!</gradient>");
    private final Template moreTags = this.flow.compile("<font:uniform>%item%</font> <lang:block.minecraft.stone> <key:key.jump> <transition:red:blue:0.4>%price%");
    private final Template markers = MiniMessageFlow.builder().nativeCompiler(false).build()
            .compile("<green>%item%</green> for <gold>%price.comma%</gold> <click:run_command:'/buy 42'>[Buy]");

    private final MiniMessageFlow.Builder keyed = MiniMessageFlow.builder();
    private final ValueKey itemKey = this.keyed.key("item");
    private final ValueKey priceKey = this.keyed.key("price");
    private final ValueKey nKey = this.keyed.key("n");
    private final MiniMessageFlow keyedFlow = this.keyed.build();
    private final Template keyedThree = this.keyedFlow.compile("<green>%item%</green> for <gold>%price%</gold> <click:run_command:'/buy 42'>[Buy %n%]");
    private final Values values = this.keyedFlow.newValues();

    private final Placeholder[] sets = new Placeholder[SETS];
    private final String[] items = new String[SETS];
    private final String[] prices = new String[SETS];
    private final String[] ns = new String[SETS];
    private int counter;

    private static String colored(int count) {
        String[] colors = {"red", "gold", "green", "aqua", "blue"};
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < count; i++) {
            text.append('<').append(colors[i % colors.length]).append(">%v").append(i).append("% ");
        }
        return text.toString();
    }

    public RenderBenchmark() {
        for (int s = 0; s < SETS; s++) {
            this.items[s] = "Sword" + s;
            this.prices[s] = Integer.toString(1000 + s);
            this.ns[s] = Integer.toString(s + 1);
            Placeholder.Builder builder = Placeholder.builder()
                    .register("name", "Steve" + s)
                    .register("item", this.items[s])
                    .register("price", this.prices[s])
                    .register("n", this.ns[s]);
            for (int i = 0; i < 20; i++) {
                builder.register("v" + i, "value" + i + "-" + s);
            }
            this.sets[s] = builder.build();
        }
    }

    @Setup
    public void shapes() {
        Map<String, Template> templates = new TreeMap<>(Map.ofEntries(
                Map.entry("one", this.one), Map.entry("joined", this.joined), Map.entry("five", this.five),
                Map.entry("twenty", this.twenty), Map.entry("three", this.three), Map.entry("shop", this.shop),
                Map.entry("clickValue", this.clickValue), Map.entry("hover", this.hover),
                Map.entry("langArgument", this.langArgument), Map.entry("gradient", this.gradient),
                Map.entry("moreTags", this.moreTags), Map.entry("markers", this.markers)));
        templates.forEach((name, template) -> System.out.println("[shape] " + name + ": " + template.kind() + ", "
                + count(template.render(this.sets[0])) + " components"));
    }

    private static int count(Component component) {
        int count = 1;
        for (Component child : component.children()) {
            count += count(child);
        }
        return count;
    }

    private Placeholder next() {
        return this.sets[this.counter++ & (SETS - 1)];
    }

    @Benchmark
    public Component one() {
        return this.one.render(this.next());
    }

    @Benchmark
    public Component joined() {
        return this.joined.render(this.next());
    }

    @Benchmark
    public Component five() {
        return this.five.render(this.next());
    }

    @Benchmark
    public Component twenty() {
        return this.twenty.render(this.next());
    }

    @Benchmark
    public Component three() {
        return this.three.render(this.next());
    }

    @Benchmark
    public String threeSend() {
        return GSON.serialize(this.three.render(this.next()));
    }

    /**
     * The same message, its values set in a {@link Values} kept between sends.
     */
    @Benchmark
    public Component threeValues() {
        int s = this.counter++ & (SETS - 1);
        this.values.set(this.itemKey, this.items[s]).set(this.priceKey, this.prices[s]).set(this.nKey, this.ns[s]);
        return this.keyedThree.render(this.values);
    }

    @Benchmark
    public Component shop() {
        return this.shop.render(this.next());
    }

    @Benchmark
    public String shopSend() {
        return GSON.serialize(this.shop.render(this.next()));
    }

    @Benchmark
    public Component clickValue() {
        return this.clickValue.render(this.next());
    }

    @Benchmark
    public Component hover() {
        return this.hover.render(this.next());
    }

    @Benchmark
    public Component langArgument() {
        return this.langArgument.render(this.next());
    }

    @Benchmark
    public Component gradient() {
        return this.gradient.render(this.next());
    }

    @Benchmark
    public Component moreTags() {
        return this.moreTags.render(this.next());
    }

    @Benchmark
    public Component markers() {
        return this.markers.render(this.next());
    }
}
