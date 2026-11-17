package fr.robie.minimessageflow.core;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;

/**
 * An immutable set of slot numbers, as bits: the {@code <key>} slots a render has no value for. Slots 0 to 63 are
 * one {@code long}; a template with more slots uses an array of them. Two sets with the same slots are equal
 * whichever form they have, so either is a cache key.
 */
@ApiStatus.Internal
final class SlotSet {

    static final SlotSet EMPTY = new SlotSet(new long[1]);

    /**
     * Never changed, and never ends with a 0 word past the first (equals and hashCode rely on it).
     */
    private final long[] words;

    private SlotSet(long @NotNull [] words) {
        this.words = words;
    }

    /**
     * The set of the slots whose bits are set in {@code bits} (slots 0 to 63).
     */
    static @NotNull SlotSet of(long bits) {
        return bits == 0 ? EMPTY : new SlotSet(new long[]{bits});
    }

    /**
     * The set of the slots whose bits are set in {@code words}, slot {@code s} being bit {@code s % 64} of word
     * {@code s / 64}. The array is copied.
     */
    static @NotNull SlotSet of(long @NotNull [] words) {
        int length = words.length;
        while (length > 1 && words[length - 1] == 0) {
            length--;
        }
        if (length == 0 || length == 1 && words[0] == 0) {
            return EMPTY;
        }
        return new SlotSet(Arrays.copyOf(words, length));
    }

    /**
     * The set of these slots (for tests and compile-time callers).
     */
    static @NotNull SlotSet ofSlots(int @NotNull ... slots) {
        long[] words = new long[1];
        for (int slot : slots) {
            words = set(words, slot);
        }
        return of(words);
    }

    /**
     * Sets the bit of a slot in {@code words}, made longer when needed.
     *
     * @return {@code words}, or a longer copy
     */
    static long @NotNull [] set(long @NotNull [] words, int slot) {
        int word = slot >>> 6;
        if (word >= words.length) {
            words = Arrays.copyOf(words, Math.max(word + 1, words.length * 2));
        }
        words[word] |= 1L << slot;
        return words;
    }

    boolean contains(int slot) {
        int word = slot >>> 6;
        return slot >= 0 && word < this.words.length && (this.words[word] & 1L << slot) != 0;
    }

    boolean isEmpty() {
        return this == EMPTY;
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof SlotSet set && Arrays.equals(this.words, set.words);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(this.words);
    }

    @Override
    public String toString() {
        StringBuilder out = new StringBuilder("[");
        for (int word = 0; word < this.words.length; word++) {
            long bits = this.words[word];
            while (bits != 0) {
                int bit = Long.numberOfTrailingZeros(bits);
                if (out.length() > 1) {
                    out.append(", ");
                }
                out.append(word * 64 + bit);
                bits &= bits - 1;
            }
        }
        return out.append(']').toString();
    }
}
