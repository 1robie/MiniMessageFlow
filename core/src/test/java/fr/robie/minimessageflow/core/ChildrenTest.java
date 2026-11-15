package fr.robie.minimessageflow.core;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.Style;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ChildrenTest {

    @Test
    @DisplayName("Built with Adventure's constructor where it can be reached, through the API otherwise")
    void directWhereReachable() {
        // Tests run with Adventure on the class path: the constructor is reached, unless turned off (testApiComponents).
        boolean expected = Boolean.parseBoolean(System.getProperty("minimessageflow.directComponents", "true"));
        assertEquals(expected, Children.direct());
    }

    @Test
    @DisplayName("A text component over children is the one Adventure's API gives, empty children left out")
    void sameAsApi() {
        Component red = Component.text("b", NamedTextColor.RED);
        Component plain = Component.text("c");
        Component[][] cases = {{red}, {red, plain}, {Component.empty(), red, Component.empty(), plain}, {Component.empty()}};
        for (Component[] items : cases) {
            Style style = Style.style(NamedTextColor.GOLD);
            Component expected = Component.text("a", style).children(List.of(items));
            Component fromArray = Children.text("a", style, Children.of(items.clone()));
            Children pushed = new Children(1);
            for (Component item : items) {
                pushed.push(item);
            }
            Component fromPushes = Children.text("a", style, pushed);
            for (Component built : new Component[]{fromArray, fromPushes}) {
                assertEquals(expected, built);
                assertEquals(built, expected);
                assertEquals(expected.hashCode(), built.hashCode());
                assertEquals(expected.children(), built.children());
                assertEquals(expected.compact(), built.compact());
                assertEquals(expected.append(plain), built.append(plain));
            }
        }
        assertSame(Component.empty(), Children.text("", Style.empty(), new Children(0)));
    }

    @Test
    @DisplayName("A component's children can't be changed")
    void immutable() {
        Children children = new Children(2);
        children.push(Component.text("a"));
        List<Component> held = Children.text("", Style.empty(), children).children();
        assertThrows(UnsupportedOperationException.class, () -> held.add(Component.text("b")));
        assertThrows(UnsupportedOperationException.class, () -> held.set(0, Component.text("b")));
        assertThrows(UnsupportedOperationException.class, () -> held.remove(0));
        assertThrows(IndexOutOfBoundsException.class, () -> held.get(1));
    }
}
