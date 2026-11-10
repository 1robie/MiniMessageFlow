package fr.robie.minimessageflow.core;

import fr.robie.minimessageflow.MiniMessageFlow;
import fr.robie.minimessageflow.Placeholder;
import fr.robie.minimessageflow.Resolver;
import fr.robie.minimessageflow.testkit.FormattingContract;
import fr.robie.minimessageflow.testkit.TestFormatter;
import org.jetbrains.annotations.NotNull;

/**
 * The stage 1 engine through {@link MiniMessageFlow#format} (compiled once, then cached).
 */
class EngineContractTest extends FormattingContract {

    @Override
    protected @NotNull TestFormatter formatter(@NotNull Placeholder globals) {
        MiniMessageFlow flow = MiniMessageFlow.builder().resolver(Resolver.of(globals)).build();
        return flow::format;
    }
}
