package fr.robie.minimessageflow.benchmarks;

import fr.robie.minimessageflow.Placeholder;
import fr.robie.minimessageflow.internal.PlaceholderScanner;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;

import java.util.concurrent.TimeUnit;

/**
 * The numbers every engine is measured against: plain {@code MiniMessage.deserialize} (one baseline per table),
 * and the placeholder scan every send pays today.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class BaselineBenchmark {

    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();

    private static final String STATIC = "<green>Welcome to the server!";
    private static final String SHOP_MINIMESSAGE = "<green><item></green> for <gold><price></gold> <click:run_command:'/buy 42'>[Buy]";
    private static final String SHOP = "<green>%item%</green> for <gold>%price%</gold> <click:run_command:'/buy %id%'>[Buy]";

    private final TagResolver shopResolvers = TagResolver.resolver(
            net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.unparsed("item", "Sword"),
            net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.unparsed("price", "1,250"));
    private final Placeholder shopValues = Placeholder.builder()
            .register("item", "Sword")
            .register("price", "1,250")
            .register("id", "42")
            .build();

    @Benchmark
    public Component miniMessageStatic() {
        return MINI_MESSAGE.deserialize(STATIC);
    }

    @Benchmark
    public Component miniMessageShop() {
        return MINI_MESSAGE.deserialize(SHOP_MINIMESSAGE, this.shopResolvers);
    }

    @Benchmark
    public String scanShop() {
        return PlaceholderScanner.replace(SHOP, (key, start) -> {
            var value = this.shopValues.get(key);
            return value == null ? null : "<v>";
        });
    }
}
