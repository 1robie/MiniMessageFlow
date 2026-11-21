package fr.robie.minimessageflow;

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.*;

class PreprocessorTest {

    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();
    private static final UnaryOperator<String> HEART = text -> text.replace(":heart:", "<3");

    @Test
    @DisplayName("Applied when compiling; source() stays the message as written")
    void compile() {
        MiniMessageFlow flow = MiniMessageFlow.builder().preprocessor(HEART).build();
        Template template = flow.compile("<red>I :heart: %name%");
        Values values = flow.newValues().set("name", "you");
        assertEquals("I <3 you", PLAIN.serialize(template.render(values, null)));
        assertEquals("<red>I :heart: %name%", template.source());
        assertEquals("<red>I :heart: %name%", template.style(net.kyori.adventure.text.format.Style.empty()).source());
        TemplateList lines = flow.compileList(List.of(":heart:", "plain"));
        assertEquals(List.of("<3", "plain"), lines.render().stream().map(PLAIN::serialize).toList());
        assertEquals(":heart:", lines.templates().getFirst().source());
    }

    @Test
    @DisplayName("Once per compile, not per render; a value is never preprocessed")
    void oncePerCompile() {
        AtomicInteger calls = new AtomicInteger();
        MiniMessageFlow flow = MiniMessageFlow.builder().preprocessor(text -> {
            calls.incrementAndGet();
            return HEART.apply(text);
        }).build();
        Template template = flow.compile("%name% :heart:");
        Values values = flow.newValues().set("name", ":heart:");
        for (int i = 0; i < 5; i++) {
            assertEquals(":heart: <3", PLAIN.serialize(template.render(values, null)));
        }
        assertEquals(1, calls.get());
    }

    @Test
    @DisplayName("Changing it compiles the templates made before again, and the format cache follows")
    void change() {
        MiniMessageFlow flow = MiniMessageFlow.builder().build();
        Template template = flow.compile("I :heart: it");
        assertEquals("I :heart: it", PLAIN.serialize(template.render()));
        assertEquals("I :heart: it", PLAIN.serialize(flow.format("I :heart: it")));

        flow.preprocessor(HEART);
        assertSame(HEART, flow.preprocessor());
        assertEquals("I <3 it", PLAIN.serialize(template.render()));
        assertEquals("I <3 it", PLAIN.serialize(flow.format("I :heart: it")));

        flow.preprocessor(null);
        assertEquals("I :heart: it", PLAIN.serialize(template.render()));
    }

    @Test
    @DisplayName("One that throws or gives null leaves the message as it is and is reported")
    void failure() {
        List<String> reported = new ArrayList<>();
        MiniMessageFlow throwing = MiniMessageFlow.builder()
                .preprocessor(text -> {
                    throw new IllegalStateException("broken");
                })
                .errorHandler((key, error) -> reported.add(key))
                .build();
        assertEquals("a :heart:", PLAIN.serialize(throwing.compile("a :heart:").render()));
        assertEquals(List.of("preprocessor"), reported);

        MiniMessageFlow nulls = MiniMessageFlow.builder().preprocessor(text -> null).build();
        assertEquals("b", PLAIN.serialize(nulls.compile("b").render()));
    }

    @Test
    @DisplayName("Text templates (commands, URLs) are not preprocessed")
    void textTemplates() {
        MiniMessageFlow flow = MiniMessageFlow.builder().preprocessor(HEART).build();
        assertEquals("say :heart:", flow.compileText("say :heart:").render(Placeholder.empty()));
    }
}
