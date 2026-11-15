package fr.robie.minimessageflow.benchmarks;

import fr.robie.minimessageflow.MiniMessageFlow;
import fr.robie.minimessageflow.Placeholder;
import fr.robie.minimessageflow.Template;
import fr.robie.minimessageflow.ValueKey;
import fr.robie.minimessageflow.Values;
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
 * runs on Adventure 5.2+:
 * {@code ./gradlew :benchmarks:jmh -PadventureVersion=5.2.0 -PfastMiniMessageVersion=2.0.0-beta.2 -Pjmh.includes=FastMiniMessage}
 * <p>
 * The two are not fed the same way: FastMiniMessage takes components the caller built, through typed keys, in a scope
 * that is reused; here the values are text in a {@link Placeholder} or a {@link Values}. A scope and a {@code Values}
 * are not thread-safe, hence {@link Scope#Thread}.
 * <ul>
 *     <li>{@code ...Same}: the same values at every render. A template here gives its last component again then.</li>
 *     <li>{@code ...Changing}: each render takes the next of {@link #SETS} sets of values made before the run
 *     (components for FastMiniMessage, a {@code Placeholder} here): the render alone.</li>
 *     <li>{@code ...Set}: each render sets new values in the scope or the {@code Values}, as a plugin does at each send:
 *     FastMiniMessage's text components are made then, here the text is set as it is.</li>
 * </ul>
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class FastMiniMessageBenchmark {

    private static final int SETS = 16;
    private static final String STATIC = "<green>Welcome to the server!";
    private static final String THREE = "<green>%item%</green> for <gold>%price%</gold> <click:run_command:'/buy 42'>[Buy %n%]";
    private static final String GRADIENT = "<gradient:red:blue>Welcome %name%!</gradient>";
    private static final String JOINED = "<green>Welcome %name%!";
    private static final String FIVE = "<red>%item% <gold>%price% <green>%n% <aqua>%name% <blue>%item%";

    /**
     * {@code %key%} as FastMiniMessage writes a placeholder, {@code <key>}.
     */
    private static String tags(String message) {
        return message.replaceAll("%(\\w+)%", "<$1>");
    }

    private final String[] items = new String[SETS];
    private final String[] prices = new String[SETS];
    private final String[] ns = new String[SETS];
    private final String[] names = new String[SETS];
    private int counter;

    // --- MiniMessageFlow

    private final MiniMessageFlow.Builder builder = MiniMessageFlow.builder();
    private final ValueKey itemKey = this.builder.key("item");
    private final ValueKey priceKey = this.builder.key("price");
    private final ValueKey nKey = this.builder.key("n");
    private final ValueKey nameKey = this.builder.key("name");
    private final MiniMessageFlow flow = this.builder.build();
    private final Template flowStatic = this.flow.compile(STATIC);
    private final Template flowThree = this.flow.compile(THREE);
    private final Template flowGradient = this.flow.compile(GRADIENT);
    private final Template flowJoined = this.flow.compile(JOINED);
    private final Template flowFive = this.flow.compile(FIVE);
    private final Values values = this.flow.newValues();
    private final Placeholder[] flowSets = new Placeholder[SETS];

    // --- FastMiniMessage

    private final PayloadSchema.Key<Component> item;
    private final PayloadSchema.Key<Component> price;
    private final PayloadSchema.Key<Component> n;
    private final PayloadSchema.Key<Component> name;
    private final PreComponentTree fastStatic;
    private final PreComponentTree fastThree;
    private final PreComponentTree fastGradient;
    private final PreComponentTree fastJoined;
    private final PreComponentTree fastFive;
    private final RenderScope.Mutable scope;
    private final Component[][] fastSets = new Component[SETS][];

    public FastMiniMessageBenchmark() {
        for (int s = 0; s < SETS; s++) {
            this.items[s] = "Sword" + s;
            this.prices[s] = Integer.toString(1000 + s);
            this.ns[s] = Integer.toString(s + 1);
            this.names[s] = "Steve" + s;
            this.flowSets[s] = Placeholder.builder()
                    .register("item", this.items[s]).register("price", this.prices[s])
                    .register("n", this.ns[s]).register("name", this.names[s]).build();
            this.fastSets[s] = new Component[]{Component.text(this.items[s]), Component.text(this.prices[s]),
                    Component.text(this.ns[s]), Component.text(this.names[s])};
        }
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
        this.fastThree = compiler.compile(tags(THREE));
        this.fastGradient = compiler.compile(tags(GRADIENT));
        this.fastJoined = compiler.compile(tags(JOINED));
        this.fastFive = compiler.compile(tags(FIVE));
        this.scope = RenderScope.mutable(schema);
        this.fastSet(0);
        this.values.set(this.itemKey, this.items[0]).set(this.priceKey, this.prices[0])
                .set(this.nKey, this.ns[0]).set(this.nameKey, this.names[0]);
    }

    /**
     * Both show the same thing, or the comparison means nothing.
     */
    @org.openjdk.jmh.annotations.Setup
    public void sameText() {
        net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer plain =
                net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText();
        PreComponentTree[] fast = {this.fastStatic, this.fastThree, this.fastGradient, this.fastJoined, this.fastFive};
        Template[] flow = {this.flowStatic, this.flowThree, this.flowGradient, this.flowJoined, this.flowFive};
        for (int i = 0; i < fast.length; i++) {
            String a = plain.serialize(fast[i].render(this.fastSet(3)));
            String b = plain.serialize(flow[i].render(this.flowSets[3]));
            if (!a.equals(b)) {
                throw new IllegalStateException("not the same text: " + a + " / " + b);
            }
        }
    }

    private int next() {
        return this.counter++ & (SETS - 1);
    }

    private RenderScope.Mutable fastSet(int s) {
        Component[] set = this.fastSets[s];
        return this.scope.payload(this.item, set[0]).payload(this.price, set[1]).payload(this.n, set[2]).payload(this.name, set[3]);
    }

    private RenderScope.Mutable fastBuilt(int s) {
        return this.scope.payload(this.item, Component.text(this.items[s])).payload(this.price, Component.text(this.prices[s]))
                .payload(this.n, Component.text(this.ns[s])).payload(this.name, Component.text(this.names[s]));
    }

    private Values flowSet(int s) {
        return this.values.set(this.itemKey, this.items[s]).set(this.priceKey, this.prices[s])
                .set(this.nKey, this.ns[s]).set(this.nameKey, this.names[s]);
    }

    // --- static

    @Benchmark
    public Component fastStatic() {
        return this.fastStatic.render();
    }

    @Benchmark
    public Component flowStatic() {
        return this.flowStatic.render();
    }

    // --- three values and a click

    @Benchmark
    public Component fastThreeSame() {
        return this.fastThree.render(this.fastSet(0));
    }

    @Benchmark
    public Component flowThreeSame() {
        return this.flowThree.render(this.flowSets[0]);
    }

    @Benchmark
    public Component fastThreeChanging() {
        return this.fastThree.render(this.fastSet(this.next()));
    }

    @Benchmark
    public Component flowThreeChanging() {
        return this.flowThree.render(this.flowSets[this.next()]);
    }

    @Benchmark
    public Component fastThreeSet() {
        return this.fastThree.render(this.fastBuilt(this.next()));
    }

    @Benchmark
    public Component flowThreeSet() {
        return this.flowThree.render(this.flowSet(this.next()));
    }

    // --- one value in text of one style

    @Benchmark
    public Component fastJoinedChanging() {
        return this.fastJoined.render(this.fastSet(this.next()));
    }

    @Benchmark
    public Component flowJoinedChanging() {
        return this.flowJoined.render(this.flowSets[this.next()]);
    }

    // --- five values in five colours

    @Benchmark
    public Component fastFiveChanging() {
        return this.fastFive.render(this.fastSet(this.next()));
    }

    @Benchmark
    public Component flowFiveChanging() {
        return this.flowFive.render(this.flowSets[this.next()]);
    }

    // --- gradient

    @Benchmark
    public Component fastGradientSame() {
        return this.fastGradient.render(this.fastSet(0));
    }

    @Benchmark
    public Component flowGradientSame() {
        return this.flowGradient.render(this.flowSets[0]);
    }

    @Benchmark
    public Component fastGradientChanging() {
        return this.fastGradient.render(this.fastSet(this.next()));
    }

    @Benchmark
    public Component flowGradientChanging() {
        return this.flowGradient.render(this.flowSets[this.next()]);
    }
}
