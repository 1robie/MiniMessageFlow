package fr.robie.minimessageflow.testkit;

import fr.robie.minimessageflow.Placeholder;
import org.jetbrains.annotations.NotNull;

/**
 * The contract holds for the reference itself, on every Adventure of the matrix: until an engine exists (stage 1),
 * this is what proves the ported behaviour.
 */
class ReferenceFormatterTest extends FormattingContract {

    @Override
    protected @NotNull TestFormatter formatter(@NotNull Placeholder globals) {
        return new ReferenceFormatter(globals);
    }
}
