package fr.robie.minimessageflow.core;

import fr.robie.minimessageflow.MiniMessageFlow;
import fr.robie.minimessageflow.Placeholder;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A value written into a tag argument stays one argument, whether the template quotes it or not.
 */
class InlineArgumentTest {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private static MiniMessageFlow flow(boolean nativeCompiler) {
        return MiniMessageFlow.builder().nativeCompiler(nativeCompiler).build();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @DisplayName("A ':' in a value does not split an unquoted argument")
    void colonInUnquotedArgument(boolean nativeCompiler) {
        MiniMessageFlow flow = flow(nativeCompiler);
        assertEquals(MINI.deserialize("<click:run_command:'/tp a:b c'>hi"),
                flow.format("<click:run_command:/tp %v%>hi", Placeholder.of("v", "a:b c")).compact());
        assertEquals(MINI.deserialize("<insert:'x:y'>hi"),
                flow.format("<insert:%v%>hi", Placeholder.of("v", "x:y")).compact());
        assertEquals(MINI.deserialize("<click:open_url:'https://example.com/a?b=c'>link"),
                flow.format("<click:open_url:%url%>link", Placeholder.of("url", "https://example.com/a?b=c")).compact());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @DisplayName("Quoting an argument changes nothing else: the action, a quoted argument, the text after")
    void otherArgumentsUnchanged(boolean nativeCompiler) {
        MiniMessageFlow flow = flow(nativeCompiler);
        assertEquals(MINI.deserialize("<click:suggest_command:'/msg x:y '>go</click> after"),
                flow.format("<click:%action%:/msg %v% >go</click> after",
                        Placeholder.of("action", "suggest_command", "v", "x:y")).compact());
        assertEquals(MINI.deserialize("<click:run_command:'/say a:b'>hi"),
                flow.format("<click:run_command:'/say %v%'>hi", Placeholder.of("v", "a:b")).compact());
        Component rendered = flow.format("<click:run_command:/tp %v%>hi", Placeholder.of("v", "Steve")).compact();
        assertEquals(ClickEvent.runCommand("/tp Steve"), rendered.clickEvent());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @DisplayName("A value cannot end a quoted argument or add one")
    void valueCannotBreakOut(boolean nativeCompiler) {
        MiniMessageFlow flow = flow(nativeCompiler);
        Component rendered = flow.format("<click:run_command:/tp %v%>hi", Placeholder.of("v", "a':run_command:/op x")).compact();
        assertEquals(ClickEvent.runCommand("/tp a:run_command:/op x"), rendered.clickEvent());
    }
}
