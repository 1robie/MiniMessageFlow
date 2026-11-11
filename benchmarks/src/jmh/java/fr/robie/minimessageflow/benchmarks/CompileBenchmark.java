package fr.robie.minimessageflow.benchmarks;

import fr.robie.minimessageflow.MiniMessageFlow;
import fr.robie.minimessageflow.Template;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;

import java.util.concurrent.TimeUnit;

/**
 * What compiling costs, next to one {@code MiniMessage.deserialize} of the same text: a message the native compiler
 * takes, one that stays with MiniMessage, one with a value in a click argument, and a static one; with the
 * compile-time check against MiniMessage (the default) and without it.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class CompileBenchmark {

    private static final String NATIVE = "<green>%item%</green> for <gold>%price.comma%</gold> <click:run_command:'/buy 42'>[Buy]";
    private static final String NATIVE_MINIMESSAGE = "<green><item></green> for <gold><price></gold> <click:run_command:'/buy 42'>[Buy]";
    private static final String OTHER_TAGS = "<selector:@p> %item% <score:steve:kills> %price%";
    private static final String MORE_TAGS = "<font:uniform>%item%</font> <lang:block.minecraft.stone> <key:key.jump> <transition:red:blue:0.4>%price%";
    private static final String CLICK_VALUE = "<click:run_command:'/tp %name%'>[tp %name%]";
    private static final String STATIC = "<green>Welcome to the server!";

    private final MiniMessageFlow checked = MiniMessageFlow.create();
    private final MiniMessageFlow unchecked = MiniMessageFlow.builder().verifyNative(false).build();
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    @Benchmark
    public Template compileNative() {
        return this.checked.compile(NATIVE);
    }

    @Benchmark
    public Template compileNativeUnchecked() {
        return this.unchecked.compile(NATIVE);
    }

    @Benchmark
    public Template compileOtherTags() {
        return this.checked.compile(OTHER_TAGS);
    }

    /**
     * Font, translatable, keybind and transition: native since stage 5.
     */
    @Benchmark
    public Template compileMoreTags() {
        return this.checked.compile(MORE_TAGS);
    }

    @Benchmark
    public Template compileClickValue() {
        return this.checked.compile(CLICK_VALUE);
    }

    @Benchmark
    public Template compileClickValueUnchecked() {
        return this.unchecked.compile(CLICK_VALUE);
    }

    @Benchmark
    public Template compileStatic() {
        return this.checked.compile(STATIC);
    }

    /**
     * The baseline: one parse of the same message (placeholders as unknown tags, no values).
     */
    @Benchmark
    public Component miniMessageNative() {
        return this.miniMessage.deserialize(NATIVE_MINIMESSAGE);
    }

    @Benchmark
    public Component miniMessageStatic() {
        return this.miniMessage.deserialize(STATIC);
    }
}
