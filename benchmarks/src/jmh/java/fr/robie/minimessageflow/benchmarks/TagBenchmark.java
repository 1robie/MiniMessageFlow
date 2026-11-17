package fr.robie.minimessageflow.benchmarks;

import fr.robie.minimessageflow.MiniMessageFlow;
import fr.robie.minimessageflow.Placeholder;
import fr.robie.minimessageflow.Template;
import net.kyori.adventure.identity.Identity;
import net.kyori.adventure.pointer.Pointered;
import net.kyori.adventure.pointer.Pointers;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.jetbrains.annotations.NotNull;
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
 * A value that depends on the viewer, three ways: a value tag ({@code <papi:player_name>}), a resolver
 * ({@code %player_name%}) and a plain {@code TagResolver} tag. Each render takes the next of {@link #VIEWERS}
 * viewers, so the value changes as when a message is sent to several players.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class TagBenchmark {

    private static final int VIEWERS = 16;

    private final Pointered[] viewers = new Pointered[VIEWERS];
    private int counter;

    private final MiniMessageFlow valueTagFlow = MiniMessageFlow.builder()
            .valueTag("papi", (arguments, viewer) -> name(viewer))
            .build();
    private final MiniMessageFlow resolverFlow = MiniMessageFlow.builder()
            .resolver((key, viewer) -> key.equals("player_name") ? name(viewer) : null)
            .build();
    private final MiniMessageFlow tagResolverFlow = MiniMessageFlow.builder()
            .tags(TagResolver.resolver("papi", (arguments, context) ->
                    Tag.selfClosingInserting(Component.text(name(context.target())))))
            .build();

    private final Template valueTag = this.valueTagFlow.compile("<gray>Hello <white><papi:player_name></white>!");
    private final Template resolver = this.resolverFlow.compile("<gray>Hello <white>%player_name%</white>!");
    private final Template tagResolver = this.tagResolverFlow.compile("<gray>Hello <white><papi:player_name></white>!");

    private static String name(Pointered viewer) {
        return viewer == null ? "nobody" : viewer.get(Identity.NAME).orElse("?");
    }

    public TagBenchmark() {
        for (int i = 0; i < VIEWERS; i++) {
            Pointers pointers = Pointers.builder().withStatic(Identity.NAME, "Player" + i).build();
            this.viewers[i] = new Pointered() {
                @Override
                public @NotNull Pointers pointers() {
                    return pointers;
                }
            };
        }
    }

    @Setup
    public void shapes() {
        Map<String, Template> templates = new TreeMap<>(Map.of(
                "valueTag", this.valueTag, "resolver", this.resolver, "tagResolver", this.tagResolver));
        templates.forEach((name, template) -> System.out.println("[shape] " + name + ": " + template.kind()));
    }

    private Pointered next() {
        return this.viewers[this.counter++ & (VIEWERS - 1)];
    }

    @Benchmark
    public Component valueTag() {
        return this.valueTag.render(Placeholder.empty(), this.next());
    }

    @Benchmark
    public Component resolver() {
        return this.resolver.render(Placeholder.empty(), this.next());
    }

    @Benchmark
    public Component tagResolver() {
        return this.tagResolver.render(Placeholder.empty(), this.next());
    }
}
