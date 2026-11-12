package fr.robie.minimessageflow.core;

import fr.robie.minimessageflow.internal.PlaceholderScanner;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link PlaceholderScanner#tagRanges} against MiniMessage's own tokenizer, on the Adventure the tests run on. The
 * tokenizer is internal to MiniMessage, so it is called by reflection: if a version moves it, this test fails and
 * says so, rather than being skipped.
 */
class TagRangesTest {

    private static final String ALPHABET = "<<>>''\"\\ab:/ %";

    @Test
    @DisplayName("Tags are where MiniMessage's tokenizer finds them")
    void sameAsMiniMessage() throws Exception {
        Random random = new Random(7);
        for (int n = 0; n < 200_000; n++) {
            StringBuilder builder = new StringBuilder();
            for (int i = random.nextInt(14); i > 0; i--) {
                builder.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
            }
            String text = builder.toString();
            assertEquals(miniMessageTags(text), ranges(text), text);
        }
        for (String text : List.of("I <3 you %name%", "<click:run_command:'/a > b'>x", "<hover:show_text:\"<red>it's\">x</hover>",
                "a \\<red> b <red>", "<red", "<a:'b\\'c'>d", "<a:'b>c", "<<red>>", "<red\\<blue>")) {
            assertEquals(miniMessageTags(text), ranges(text), text);
        }
    }

    private static List<Integer> ranges(String text) {
        List<Integer> ranges = new ArrayList<>();
        for (int index : PlaceholderScanner.tagRanges(text)) {
            ranges.add(index);
        }
        return ranges;
    }

    /**
     * The start and end of every token of MiniMessage's first pass that is not text.
     */
    private static List<Integer> miniMessageTags(String text) throws Exception {
        Class<?> parser = Class.forName("net.kyori.adventure.text.minimessage.internal.parser.TokenParser");
        Method tokenize = null;
        for (Method method : parser.getDeclaredMethods()) {
            if (method.getName().equals("tokenize") && method.getParameterTypes()[0] == String.class) {
                tokenize = method;
            }
        }
        if (tokenize == null) {
            throw new IllegalStateException("TokenParser.tokenize(String...) not found in this MiniMessage");
        }
        tokenize.setAccessible(true);
        Object tokens = tokenize.getParameterCount() == 1 ? tokenize.invoke(null, text) : tokenize.invoke(null, text, false);
        List<Integer> ranges = new ArrayList<>();
        for (Object token : (List<?>) tokens) {
            if (!String.valueOf(call(token, "type")).equals("TEXT")) {
                ranges.add((Integer) call(token, "startIndex"));
                ranges.add((Integer) call(token, "endIndex"));
            }
        }
        return ranges;
    }

    private static Object call(Object target, String name) throws Exception {
        Method method = target.getClass().getDeclaredMethod(name);
        method.setAccessible(true);
        return method.invoke(target);
    }
}
