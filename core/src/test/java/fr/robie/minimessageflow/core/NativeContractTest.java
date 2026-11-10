package fr.robie.minimessageflow.core;

import fr.robie.minimessageflow.MiniMessageFlow;
import fr.robie.minimessageflow.Placeholder;
import fr.robie.minimessageflow.Resolver;
import fr.robie.minimessageflow.testkit.FormattingContract;
import fr.robie.minimessageflow.testkit.TestFormatter;
import org.jetbrains.annotations.NotNull;

/**
 * The native compiler on its own: no check against MiniMessage at compile, so a message it reads wrong fails here
 * instead of quietly going to MiniMessage.
 */
class NativeContractTest extends FormattingContract {

    @Override
    protected @NotNull TestFormatter formatter(@NotNull Placeholder globals) {
        MiniMessageFlow flow = MiniMessageFlow.builder().resolver(Resolver.of(globals)).verifyNative(false).build();
        return flow::format;
    }
}
