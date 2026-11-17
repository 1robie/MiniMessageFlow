package fr.robie.minimessageflow.compat.adventure4;

import net.kyori.adventure.pointer.Pointered;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.Inserting;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * A tag resolver asked directly, as MiniMessage asks it while parsing: Adventure 4's parsing context and argument
 * queue are interfaces anyone can implement (Adventure 5 seals them, so this class is only loaded on 4.x).
 */
final class DirectTags {

    private DirectTags() {
    }

    /**
     * The component an inserting tag gives, or null for another kind of tag or none.
     */
    static @Nullable Component inserted(@NotNull TagResolver resolver, @NotNull String name, @NotNull List<String> arguments,
                                        @Nullable Pointered target) {
        Tag tag = resolver.resolve(name, new DirectArguments(arguments), new DirectContext(target, resolver));
        return tag instanceof Inserting inserting ? inserting.value() : null;
    }

    /**
     * A tag's arguments, as MiniMessage gives them to a resolver.
     */
    static final class DirectArguments implements net.kyori.adventure.text.minimessage.tag.resolver.ArgumentQueue {
        private final List<String> values;
        private int next;

        DirectArguments(@NotNull List<String> values) {
            this.values = values;
        }

        @Override
        public Tag.@NotNull Argument pop() {
            if (this.next >= this.values.size()) {
                throw new DirectParsingException("missing argument " + (this.next + 1));
            }
            return argument(this.values.get(this.next++));
        }

        @Override
        public Tag.@NotNull Argument popOr(@NotNull String errorMessage) {
            if (this.next >= this.values.size()) {
                throw new DirectParsingException(errorMessage);
            }
            return this.pop();
        }

        @Override
        public Tag.@NotNull Argument popOr(
                @NotNull java.util.function.Supplier<String> errorMessage) {
            if (this.next >= this.values.size()) {
                throw new DirectParsingException(errorMessage.get());
            }
            return this.pop();
        }

        @Override
        public Tag.@Nullable Argument peek() {
            return this.next < this.values.size() ? argument(this.values.get(this.next)) : null;
        }

        @Override
        public boolean hasNext() {
            return this.next < this.values.size();
        }

        @Override
        public void reset() {
            this.next = 0;
        }

        private static Tag.@NotNull Argument argument(@NotNull String value) {
            return () -> value;
        }
    }

    /**
     * The context MiniMessage gives a tag: the viewer as the target, and a parse with the same resolver for a tag that
     * formats text of its own.
     */
    static final class DirectContext implements net.kyori.adventure.text.minimessage.Context {
        private final @Nullable Pointered target;
        private final TagResolver resolver;

        DirectContext(@Nullable Pointered target, @NotNull TagResolver resolver) {
            this.target = target;
            this.resolver = resolver;
        }

        @Override
        public @Nullable Pointered target() {
            return this.target;
        }

        @Override
        public @NotNull Pointered targetOrThrow() {
            if (this.target == null) {
                throw new DirectParsingException("this tag needs a viewer");
            }
            return this.target;
        }

        @Override
        public <T extends Pointered> @NotNull T targetAsType(@NotNull Class<T> type) {
            if (!type.isInstance(this.target)) {
                throw new DirectParsingException("this tag needs a viewer of type " + type.getSimpleName());
            }
            return type.cast(this.target);
        }

        @Override
        public @NotNull Component deserialize(@NotNull String message) {
            return this.deserialize(message, TagResolver.empty());
        }

        @Override
        public @NotNull Component deserialize(@NotNull String message, @NotNull TagResolver resolver) {
            TagResolver both = TagResolver.resolver(this.resolver, resolver);
            return this.target == null ? MiniMessage.miniMessage().deserialize(message, both)
                    : MiniMessage.miniMessage().deserialize(message, this.target, both);
        }

        @Override
        public @NotNull Component deserialize(@NotNull String message, @NotNull TagResolver @NotNull ... resolvers) {
            return this.deserialize(message, TagResolver.resolver(resolvers));
        }

        @Override
        public net.kyori.adventure.text.minimessage.@NotNull ParsingException newException(@NotNull String message,
                net.kyori.adventure.text.minimessage.tag.resolver.@NotNull ArgumentQueue arguments) {
            return new DirectParsingException(message);
        }

        @Override
        public net.kyori.adventure.text.minimessage.@NotNull ParsingException newException(@NotNull String message) {
            return new DirectParsingException(message);
        }

        @Override
        public net.kyori.adventure.text.minimessage.@NotNull ParsingException newException(@NotNull String message,
                @Nullable Throwable cause, net.kyori.adventure.text.minimessage.tag.resolver.@NotNull ArgumentQueue arguments) {
            return new DirectParsingException(message, cause);
        }

        /**
         * Adventure 5's (not on 4.x's interface, so no {@code @Override}): no virtual components here.
         */
        @SuppressWarnings("unused")
        public boolean emitVirtuals() {
            return false;
        }
    }

    /**
     * A tag's error when it is asked directly: the placeholder fails, the error handler is told.
     */
    static final class DirectParsingException extends net.kyori.adventure.text.minimessage.ParsingException {
        private static final long serialVersionUID = 1L;

        DirectParsingException(@NotNull String message) {
            super(message);
        }

        DirectParsingException(@NotNull String message, @Nullable Throwable cause) {
            super(message, cause);
        }

        @Override
        public @Nullable String originalText() {
            return null;
        }

        @Override
        public @NotNull String detailMessage() {
            return String.valueOf(this.getMessage());
        }

        @Override
        public int startIndex() {
            return -1;
        }

        @Override
        public int endIndex() {
            return -1;
        }
    }
}
