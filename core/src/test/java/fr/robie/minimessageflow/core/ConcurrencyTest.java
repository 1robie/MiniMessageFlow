package fr.robie.minimessageflow.core;

import fr.robie.minimessageflow.MiniMessageFlow;
import fr.robie.minimessageflow.Placeholder;
import fr.robie.minimessageflow.Resolver;
import fr.robie.minimessageflow.Template;
import fr.robie.minimessageflow.ValueKey;
import fr.robie.minimessageflow.Values;
import fr.robie.minimessageflow.testkit.ComponentRuns;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A template is shared by the threads that render it, and keeps what it has worked out between renders (a
 * gradient's colours by length, the styled text of a resolver, the tree MiniMessage parsed). Every thread must get
 * what a single thread gets, whatever the others are rendering.
 */
class ConcurrencyTest {

    private static final int THREADS = 8;
    private static final int ROUNDS = 3_000;

    private static final String[] MESSAGES = {
            "<gradient:red:blue>Welcome %name%!</gradient>",
            "<rainbow>%name% and <b>%other%</b></rainbow> end",
            "<gray>[%server%] <white>%name% <gold>%star%",
            "<green>%name%</green> has <gold>%coins.comma%</gold> <click:run_command:'/pay %name%'>[pay]</click>",
            "<hover:show_text:'<gray>%coins.comma% coins of %name%'>%name%</hover>",
            "<lang:chat.type.text:'%name%':'<green>%other%'>",
            "<selector:@p> %name% <score:steve:kills> %coins%",
            "<gradient:red:blue>%star%</gradient> %name%",
    };

    private static MiniMessageFlow.Builder builder() {
        return MiniMessageFlow.builder().resolver(Resolver.of(Placeholder.of("server", "<gold>Lobby")));
    }

    private static String name(int i) {
        // Lengths from 1 to 70: under and over what a gradient keeps.
        return "N" + "x".repeat(i % 70) + (i % 7);
    }

    private static Component star(int i) {
        return i % 3 == 0 ? Component.text("*", NamedTextColor.YELLOW) : Component.text("+");
    }

    private static Placeholder values(int i) {
        return Placeholder.builder().register("name", name(i)).register("other", "o" + (i % 11))
                .register("coins", String.valueOf(i * 37)).register("star", star(i)).build();
    }

    @Test
    @DisplayName("Many threads rendering the same templates get what one thread gets")
    void sharedTemplates() throws Exception {
        int distinct = 97;
        // What each message gives for each set of values, from templates no other thread touches.
        MiniMessageFlow reference = builder().build();
        List<List<List<?>>> expected = new ArrayList<>();
        for (String message : MESSAGES) {
            Template template = reference.compile(message);
            List<List<?>> perValue = new ArrayList<>();
            for (int i = 0; i < distinct; i++) {
                perValue.add(ComponentRuns.of(template.render(values(i))));
            }
            expected.add(perValue);
        }

        MiniMessageFlow.Builder shared = builder();
        ValueKey nameKey = shared.key("name");
        ValueKey otherKey = shared.key("other");
        ValueKey coinsKey = shared.key("coins");
        ValueKey starKey = shared.key("star");
        MiniMessageFlow flow = shared.build();
        List<Template> templates = new ArrayList<>();
        for (String message : MESSAGES) {
            templates.add(flow.compile(message));
        }

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<?>> running = new ArrayList<>();
            for (int thread = 0; thread < THREADS; thread++) {
                int offset = thread * 13;
                running.add(pool.submit(() -> {
                    // One Values per thread, as its contract asks.
                    Values own = flow.newValues();
                    start.await();
                    for (int round = 0; round < ROUNDS; round++) {
                        int i = (round + offset) % distinct;
                        int m = (round + offset) % MESSAGES.length;
                        Template template = templates.get(m);
                        Component rendered;
                        if (round % 2 == 0) {
                            rendered = template.render(values(i));
                        } else {
                            own.set(nameKey, name(i)).set(otherKey, "o" + (i % 11)).set(coinsKey, (long) i * 37).set(starKey, star(i));
                            rendered = template.render(own);
                        }
                        assertEquals(expected.get(m).get(i), ComponentRuns.of(rendered), MESSAGES[m] + " with values " + i);
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : running) {
                future.get(120, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
