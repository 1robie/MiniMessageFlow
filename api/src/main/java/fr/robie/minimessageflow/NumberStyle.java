package fr.robie.minimessageflow;

/**
 * How the number modifiers ({@code comma}, {@code add:n}, {@code 2f}, {@code plural}...) read a value's separators,
 * and how they write the numbers they give. Set for one instance with {@code MiniMessageFlow.builder().numberStyle(...)}
 * (or {@link ModifierSet.Builder#numberStyle}); a plugin can let its users choose it in its configuration, for example
 * with {@code NumberStyle.valueOf(config.getString("number-style").toUpperCase(Locale.ROOT))}.
 * <p>
 * A number written with digits only ({@code 1250}, {@code -3}) reads the same in every style. A value whose separators
 * do not fit the style is not a number: the modifier fails and the placeholder is kept as written.
 *
 * <table>
 *   <caption>What each style reads and writes</caption>
 *   <tr><th></th><th>{@code 1,234}</th><th>{@code 1,5}</th><th>{@code 1.234,5}</th><th>{@code 1,234.5}</th><th>writes</th></tr>
 *   <tr><td>{@link #AUTO}</td><td>1234</td><td>1.5</td><td>1234.5</td><td>1234.5</td><td>{@code 1,234.50}</td></tr>
 *   <tr><td>{@link #ENGLISH}</td><td>1234</td><td>not a number</td><td>not a number</td><td>1234.5</td><td>{@code 1,234.50}</td></tr>
 *   <tr><td>{@link #EUROPEAN}</td><td>1.234</td><td>1.5</td><td>1234.5</td><td>not a number</td><td>{@code 1.234,50}</td></tr>
 * </table>
 */
public enum NumberStyle {

    /**
     * The default. Reads both kinds: when a value has both {@code .} and {@code ,}, the last one is the decimal point;
     * a separator that comes several times ({@code 1,234,567}, {@code 1.234.567}) separates thousands; a single
     * {@code ,} followed by exactly three digits, after a whole part other than {@code 0}, separates thousands
     * ({@code 1,234} is 1234, {@code 0,125} is 0.125); any other single {@code ,} or {@code .} is the decimal point; a
     * space separates thousands. Writes the {@link #ENGLISH} way.
     */
    AUTO,

    /**
     * {@code .} is the decimal point, {@code ,} separates thousands: reads and writes {@code 1,234.50}.
     */
    ENGLISH,

    /**
     * {@code ,} is the decimal point, {@code .} or a space separates thousands: reads {@code 1.234,50} and
     * {@code 1 234,50}, writes {@code 1.234,50}.
     */
    EUROPEAN
}
