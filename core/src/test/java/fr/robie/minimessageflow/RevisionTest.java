package fr.robie.minimessageflow;

import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RevisionTest {

    @Test
    @DisplayName("Changes with every change of definitions, not with renders or unchanged settings")
    void revision() {
        MiniMessageFlow flow = MiniMessageFlow.builder().build();
        int start = flow.revision();

        flow.compile("<red>%name%").render(flow.newValues().set("name", "x"), null);
        flow.format("hello");
        assertEquals(start, flow.revision());

        flow.tags(TagResolver.empty());
        int afterTags = flow.revision();
        assertNotEquals(start, afterTags);

        flow.valueTag("now", (arguments, viewer) -> "1");
        int afterValueTag = flow.revision();
        assertNotEquals(afterTags, afterValueTag);

        flow.preprocessor(text -> text);
        assertNotEquals(afterValueTag, flow.revision());

        int beforeSame = flow.revision();
        flow.numberStyle(flow.numberStyle());
        assertEquals(beforeSame, flow.revision());
    }
}
