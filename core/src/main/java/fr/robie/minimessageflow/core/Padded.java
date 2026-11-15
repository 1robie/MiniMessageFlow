package fr.robie.minimessageflow.core;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * A field that the threads rendering one template write now and then and read at every render, on cache lines of its
 * own: a write then makes the other threads read again this field only, not the template's fields next to it. The JVM
 * lays out a class's fields after those of its superclass, so the padding before and after stays around the field.
 */
@ApiStatus.Internal
final class Padded<T> extends PaddedField<T> {
    @SuppressWarnings("unused")
    long q0, q1, q2, q3, q4, q5, q6, q7;

    @Nullable T get() {
        return this.value;
    }

    void set(@Nullable T value) {
        this.value = value;
    }
}

abstract class PaddedField<T> extends PadBefore {
    /**
     * Not volatile: a thread that sees an older value only does more work.
     */
    @Nullable T value;
}
