package fr.robie.minimessageflow.core;

import fr.robie.minimessageflow.MiniMessageFlow;
import fr.robie.minimessageflow.testkit.ComponentRuns;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.junit.jupiter.api.Test;

/**
 * A closing tag where MiniMessage drops it or keeps it as text: the native compiler, unchecked, shows the same.
 */
class ClosingTagsTest {
    @Test
    @org.junit.jupiter.api.DisplayName("Closing tags, matched or not, read as MiniMessage reads them")
    void closingTags() {
        java.util.List<String> diffs = new java.util.ArrayList<>();
        MiniMessageFlow flow = MiniMessageFlow.builder().verifyNative(false).build();
        for (String tag : new String[]{"reset", "newline", "br", "sprite", "selector", "key", "keybind", "lang", "tr", "translate",
                "score", "nbt", "data", "head", "pride", "bold", "red", "gradient", "rainbow", "click", "hover", "insert", "font", "shadow"}) {
            for (String m : new String[]{"a</" + tag + ">c", "<red>a</" + tag + ">b", "a<" + tag + "></" + tag + ">c"}) {
                Object mm, nat;
                try { mm = ComponentRuns.of(MiniMessage.miniMessage().deserialize(m)); } catch (Exception e) { mm = "ERR " + e; }
                try { nat = ComponentRuns.of(flow.format(m)); } catch (Exception e) { nat = "ERR " + e; }
                if (!mm.equals(nat)) {
                    diffs.add( m + " | mm=" + mm + " | nat=" + nat);
                }
            }
        }
        if (!diffs.isEmpty()) {
            throw new AssertionError(String.join(System.lineSeparator(), diffs));
        }
    }
}
