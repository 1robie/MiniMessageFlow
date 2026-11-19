package fr.robie.minimessageflow;

/**
 * What a placeholder gives when it has a value but its modifiers don't apply: an unknown modifier
 * ({@code %name.nope%}), or one that fails ({@code %name.int%} on a word, {@code %name.max%} without its argument).
 * A placeholder without a value stays as written either way, so a later step (PlaceholderAPI) can still read it.
 */
public enum FailedModifier {
    /**
     * The placeholder as written, {@code %name.nope%}. The default.
     */
    WRITTEN,
    /**
     * The empty text, as zMenu's placeholders and other string parsers give.
     */
    EMPTY
}
