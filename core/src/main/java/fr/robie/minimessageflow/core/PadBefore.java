package fr.robie.minimessageflow.core;

import org.jetbrains.annotations.ApiStatus;

/**
 * The padding before a field written by the threads that render (64 bytes, a cache line).
 */
@ApiStatus.Internal
abstract class PadBefore {
    @SuppressWarnings("unused")
    long p0, p1, p2, p3, p4, p5, p6, p7;
}
