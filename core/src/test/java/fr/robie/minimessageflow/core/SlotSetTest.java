package fr.robie.minimessageflow.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SlotSetTest {

    @Test
    @DisplayName("Slots around the 64-bit boundary are kept and found")
    void boundary() {
        SlotSet set = SlotSet.ofSlots(0, 63, 64, 65, 200);
        for (int slot : new int[]{0, 63, 64, 65, 200}) {
            assertTrue(set.contains(slot), "slot " + slot);
        }
        for (int slot : new int[]{1, 62, 66, 199, 201, 1000, -1}) {
            assertFalse(set.contains(slot), "slot " + slot);
        }
        assertEquals("[0, 63, 64, 65, 200]", set.toString());
    }

    @Test
    @DisplayName("The same slots are equal whichever way the set was made")
    void equality() {
        assertEquals(SlotSet.of(1L << 5 | 1L << 63), SlotSet.ofSlots(5, 63));
        assertEquals(SlotSet.of(new long[]{1L << 5, 0, 0}), SlotSet.ofSlots(5));
        assertEquals(SlotSet.ofSlots(5).hashCode(), SlotSet.of(new long[]{1L << 5, 0}).hashCode());
        assertNotEquals(SlotSet.ofSlots(5), SlotSet.ofSlots(5, 64));
        assertNotEquals(SlotSet.ofSlots(64), SlotSet.ofSlots(0));
    }

    @Test
    @DisplayName("No slot is the empty set")
    void empty() {
        assertSame(SlotSet.EMPTY, SlotSet.of(0L));
        assertSame(SlotSet.EMPTY, SlotSet.of(new long[3]));
        assertSame(SlotSet.EMPTY, SlotSet.ofSlots());
        assertTrue(SlotSet.EMPTY.isEmpty());
        assertFalse(SlotSet.ofSlots(0).isEmpty());
        assertFalse(SlotSet.EMPTY.contains(0));
    }
}
