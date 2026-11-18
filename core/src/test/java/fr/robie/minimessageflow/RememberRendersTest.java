package fr.robie.minimessageflow;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RememberRendersTest {

    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    @Test
    @DisplayName("Values that remember renders give the same component while their values are the same, per viewer")
    void perViewer() {
        MiniMessageFlow.Builder builder = MiniMessageFlow.builder();
        ValueKey name = builder.key("name");
        ValueKey coins = builder.key("coins");
        MiniMessageFlow flow = builder.build();
        Template template = flow.compile("<gray>%name%: <gold>%coins.comma% <click:run_command:'/pay %name%'>[pay]");
        Values steve = flow.newValues().rememberRenders(true).set(name, "Steve").set(coins, 1000L);
        Values alex = flow.newValues().rememberRenders(true).set(name, "Alex").set(coins, 5L);

        Component steveFirst = template.render(steve, null);
        Component alexFirst = template.render(alex, null);
        for (int i = 0; i < 20; i++) {
            assertSame(steveFirst, template.render(steve, null));
            assertSame(alexFirst, template.render(alex, null));
        }

        steve.set(coins, 2000L);
        Component steveChanged = template.render(steve, null);
        assertNotSame(steveFirst, steveChanged);
        assertEquals("Steve: 2,000 [pay]", PLAIN.serialize(steveChanged));
        assertSame(steveChanged, template.render(steve, null));
        assertSame(alexFirst, template.render(alex, null));
    }

    @Test
    @DisplayName("A child layer remembers its own renders, its parent's values included")
    void layers() {
        MiniMessageFlow.Builder builder = MiniMessageFlow.builder();
        ValueKey page = builder.key("page");
        ValueKey index = builder.key("index");
        MiniMessageFlow flow = builder.build();
        Template template = flow.compile("<red>%page%-%index%");
        Values menu = flow.newValues().set(page, 1L);
        Values slot = menu.child().rememberRenders(true).set(index, 4L);
        Component first = template.render(slot, null);
        assertSame(first, template.render(slot, null));
        menu.set(page, 2L);
        Component next = template.render(slot, null);
        assertNotSame(first, next);
        assertEquals("2-4", PLAIN.serialize(next));
    }

    @Test
    @DisplayName("A render with the same values, remembered, allocates nothing")
    void noAllocation() {
        java.lang.management.ThreadMXBean bean = java.lang.management.ManagementFactory.getThreadMXBean();
        if (!(bean instanceof com.sun.management.ThreadMXBean threads) || !threads.isThreadAllocatedMemorySupported()) {
            return;
        }
        MiniMessageFlow.Builder builder = MiniMessageFlow.builder();
        ValueKey name = builder.key("name");
        ValueKey rank = builder.key("rank");
        MiniMessageFlow flow = builder.build();
        Template template = flow.compile("<gray>%rank% <white>%name% <click:run_command:'/msg %name%'>[msg]");
        Values menu = flow.newValues().setFormatted(rank, "&c[Admin]");
        Values values = menu.child().rememberRenders(true).set(name, "Steve");
        for (int i = 0; i < 20_000; i++) {
            template.render(values, null);
        }
        long before = threads.getCurrentThreadAllocatedBytes();
        Component last = null;
        for (int i = 0; i < 10_000; i++) {
            last = template.render(values, null);
        }
        long allocated = threads.getCurrentThreadAllocatedBytes() - before;
        assertNotNull(last);
        assertTrue(allocated < 10_000, "allocated " + allocated + " bytes over 10,000 renders");
    }

    @Test
    @DisplayName("forgetRenders and turning it off")
    void forget() {
        MiniMessageFlow.Builder builder = MiniMessageFlow.builder();
        ValueKey name = builder.key("name");
        MiniMessageFlow flow = builder.build();
        Template template = flow.compile("<red>%name%");
        Values values = flow.newValues().rememberRenders(true).set(name, "Steve");
        assertTrue(values.remembersRenders());
        Component first = template.render(values, null);
        values.forgetRenders();
        assertEquals(PLAIN.serialize(first), PLAIN.serialize(template.render(values, null)));
        values.rememberRenders(false);
        assertFalse(values.remembersRenders());
        assertEquals("Steve", PLAIN.serialize(template.render(values, null)));
    }
}
