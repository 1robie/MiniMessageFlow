package fr.robie.minimessageflow.core;

import fr.robie.minimessageflow.MiniMessageFlow;
import fr.robie.minimessageflow.Placeholder;
import fr.robie.minimessageflow.Template;
import fr.robie.minimessageflow.testkit.ComponentRuns;
import fr.robie.minimessageflow.testkit.ReferenceFormatter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * More {@code <key>} placeholders than a {@code long} has bits, some without a value.
 */
class ManySlotsTest {

    private static final int KEYS = 70;

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @DisplayName("A template with 70 <key> placeholders, some unknown below and above slot 64")
    void manySlots(boolean nativeCompiler) {
        StringBuilder message = new StringBuilder();
        for (int i = 0; i < KEYS; i++) {
            message.append(i % 2 == 0 ? "<red>" : "<blue>").append("<k").append(i).append("> ");
        }
        Template template = MiniMessageFlow.builder().nativeCompiler(nativeCompiler).build().compile(message.toString());
        ReferenceFormatter reference = new ReferenceFormatter(Placeholder.empty());
        // Each set leaves out other keys: below 64 only, above only, both.
        int[][] missing = {{3}, {65, 69}, {0, 63, 64, 66}, {}};
        for (int[] skip : missing) {
            Placeholder.Builder builder = Placeholder.builder();
            outer:
            for (int i = 0; i < KEYS; i++) {
                for (int s : skip) {
                    if (s == i) {
                        continue outer;
                    }
                }
                builder.register("k" + i, "v" + i);
            }
            Placeholder values = builder.build();
            for (int round = 0; round < 2; round++) { // the second render reads the cached variant
                assertEquals(ComponentRuns.of(reference.format(message.toString(), values, null)),
                        ComponentRuns.of(template.render(values)), java.util.Arrays.toString(skip));
            }
        }
    }
}
