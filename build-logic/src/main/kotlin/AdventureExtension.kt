import org.gradle.api.provider.Property

/**
 * How a module gets Adventure.
 */
abstract class AdventureExtension {
    /**
     * True for a module compiled against a fixed Adventure (`compat/adventure-N`): its compile classpath keeps the
     * version it declares. Its tests still run on `-PadventureVersion`, like every other module.
     */
    abstract val pinned: Property<Boolean>
}
