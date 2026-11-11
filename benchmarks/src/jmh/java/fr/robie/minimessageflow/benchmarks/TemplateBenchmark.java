package fr.robie.minimessageflow.benchmarks;

import fr.robie.minimessageflow.MiniMessageFlow;
import fr.robie.minimessageflow.Placeholder;
import fr.robie.minimessageflow.Resolver;
import fr.robie.minimessageflow.Template;
import net.kyori.adventure.text.Component;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;

import java.util.concurrent.TimeUnit;

/**
 * Stage 1 engine. Compare with {@link BaselineBenchmark} (plain MiniMessage) in the same run.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class TemplateBenchmark {

    private final MiniMessageFlow flow = MiniMessageFlow.builder()
            .resolver(Resolver.of(Placeholder.of("server", "<gold>Lobby")))
            .build();

    private final Template staticTemplate = this.flow.compile("<green>Welcome to the server!");
    private final Template shop = this.flow.compile("<green>%item%</green> for <gold>%price.comma%</gold> <click:run_command:'/buy 42'>[Buy]");
    private final Template shopTags = this.flow.compile("<green><item></green> for <gold><price:comma></gold> <click:run_command:'/buy 42'>[Buy]");
    private final Template global = this.flow.compile("<gray>[%server%] <white>%name%");
    private final Template clickValue = this.flow.compile("<click:run_command:'/tp %name%'>[tp %name%]");
    private final Template gradient = this.flow.compile("<gradient:red:blue>Welcome %name%!</gradient>");

    /**
     * Stage 1's engine (markers swapped in MiniMessage's tree), for comparison.
     */
    private final Template shopMarkers = MiniMessageFlow.builder().nativeCompiler(false).build()
            .compile("<green>%item%</green> for <gold>%price.comma%</gold> <click:run_command:'/buy 42'>[Buy]");
    private final Template shopPlain = this.flow.compile("<green>%item%</green> for <gold>%price%</gold> <click:run_command:'/buy %id%'>[Buy]");
    private final Template threeValues = this.flow.compile("<green>%item%</green> for <gold>%price%</gold> <click:run_command:'/buy 42'>[Buy %n%]");

    /**
     * Font, translatable, keybind and transition (native since stage 5), and the same values with tags that stay with
     * MiniMessage.
     */
    private final Template moreTags = this.flow.compile("<font:uniform>%item%</font> <lang:block.minecraft.stone> <key:key.jump> <transition:red:blue:0.4>%price%");
    private final Template otherTags = this.flow.compile("<selector:@p> %item% <score:steve:kills> %price%");

    private final Placeholder shopValues = Placeholder.builder()
            .register("item", "Sword")
            .register("price", "1250")
            .build();
    private final Placeholder name = Placeholder.of("name", "Steve");

    private int counter;

    @Benchmark
    public Component staticRender() {
        return this.staticTemplate.render();
    }

    @Benchmark
    public Component staticFormat() {
        return this.flow.format("<green>Welcome to the server!");
    }

    @Benchmark
    public Component shopRender() {
        return this.shop.render(this.shopValues);
    }

    /**
     * Sprite and head (object components): native on Adventure 5, MiniMessage's on 4.x.
     */
    private final Template objects = this.flow.compile("<sprite:item/diamond> %item% for %price% <head:Notch>");

    @Benchmark
    public Component objectsRender() {
        return this.objects.render(this.shopValues);
    }

    @Benchmark
    public Component moreTagsRender() {
        return this.moreTags.render(this.shopValues);
    }

    @Benchmark
    public Component otherTagsRender() {
        return this.otherTags.render(this.shopValues);
    }

    @Benchmark
    public Component shopRenderMarkers() {
        return this.shopMarkers.render(this.shopValues);
    }

    /**
     * The plan's target case: 3 placeholders and a click, no modifier.
     */
    @Benchmark
    public Component threePlaceholdersClick() {
        return this.threeValues.render(this.threeValuesPlaceholder);
    }

    private final Placeholder threeValuesPlaceholder = Placeholder.builder()
            .register("item", "Sword")
            .register("price", "1,250")
            .register("n", "1")
            .build();

    @Benchmark
    public Component shopKeyTagsRender() {
        return this.shopTags.render(this.shopValues);
    }

    @Benchmark
    public Component shopFormat() {
        return this.flow.format("<green>%item%</green> for <gold>%price.comma%</gold> <click:run_command:'/buy 42'>[Buy]", this.shopValues);
    }

    @Benchmark
    public Component globalRender() {
        return this.global.render(this.name);
    }

    @Benchmark
    public Component clickValueSame() {
        return this.clickValue.render(this.name);
    }

    @Benchmark
    public Component clickValueChanging() {
        return this.clickValue.render(Placeholder.of("name", "P" + (this.counter++ & 1023)));
    }

    @Benchmark
    public Component gradientSame() {
        return this.gradient.render(this.name);
    }

    @Benchmark
    public Component gradientChanging() {
        return this.gradient.render(Placeholder.of("name", "P" + (this.counter++ & 1023)));
    }
}
