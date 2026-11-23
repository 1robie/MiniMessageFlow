package fr.robie.minimessageflow.core;

import fr.robie.minimessageflow.MiniMessageFlow;
import fr.robie.minimessageflow.Template;
import fr.robie.minimessageflow.TextTemplate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FollowedTemplatesTest {

    private static EngineSource source(MiniMessageFlow flow) throws ReflectiveOperationException {
        Field field = MiniMessageFlow.class.getDeclaredField("source");
        field.setAccessible(true);
        return (EngineSource) field.get(flow);
    }

    @Test
    @DisplayName("Templates no one keeps are collected and leave the source")
    void collected() throws Exception {
        MiniMessageFlow flow = MiniMessageFlow.create();
        EngineSource source = source(flow);
        List<Object> kept = new ArrayList<>();
        kept.add(flow.compile("<green>kept %a%"));
        kept.add(flow.compileText("kept %a%"));
        for (int i = 0; i < 10_000; i++) {
            flow.compile("<red>dropped " + i);
            flow.compileText("dropped " + i);
        }
        long end = System.currentTimeMillis() + 10_000;
        while (source.followed() > 2 && System.currentTimeMillis() < end) {
            System.gc();
            Thread.sleep(10);
        }
        assertEquals(2, source.followed());
        flow.constant("a", "A");
        assertEquals("kept A", ((TextTemplate) kept.get(1)).render());
        assertTrue(((Template) kept.getFirst()).isStatic());
    }

    @Test
    @DisplayName("format() keeps the engine's own templates, not followed ones")
    void formatNotFollowed() throws Exception {
        MiniMessageFlow flow = MiniMessageFlow.create();
        EngineSource source = source(flow);
        int before = source.followed();
        for (int i = 0; i < 100; i++) {
            flow.format("<red>message " + i);
            flow.formatText("text " + i, fr.robie.minimessageflow.Placeholder.empty());
        }
        assertEquals(before, source.followed());
    }
}
