package fr.robie.minimessageflow.testkit;

import fr.robie.minimessageflow.Placeholder;
import net.kyori.adventure.pointer.Pointered;
import net.kyori.adventure.text.Component;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * An engine under test, seen by {@link FormattingContract}: a message, its values and the viewer in, a component out.
 */
@FunctionalInterface
public interface TestFormatter {

    @NotNull Component format(@NotNull String message, @NotNull Placeholder placeholders, @Nullable Pointered viewer);
}
