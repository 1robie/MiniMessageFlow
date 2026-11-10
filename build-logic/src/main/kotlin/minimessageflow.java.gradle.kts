import org.gradle.api.tasks.testing.logging.TestExceptionFormat

plugins {
    `java-library`
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")
fun lib(alias: String) = libs.findLibrary(alias).get()

/** The Adventure core is compiled against and every test runs on: `-PadventureVersion=5.2.0`. */
val adventureVersion: String = providers.gradleProperty("adventureVersion").get()

val adventure = extensions.create<AdventureExtension>("adventure")
adventure.pinned.convention(false)

java {
    toolchain.languageVersion = JavaLanguageVersion.of(21)
    withSourcesJar()
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = 21
    options.compilerArgs.addAll(listOf("-Xlint:all,-processing,-serial", "-Werror"))
}

// Every net.kyori:adventure-* artifact follows -PadventureVersion, except the compile classpath of a pinned module.
val pinned = adventure.pinned
configurations.configureEach {
    val configurationName = name
    resolutionStrategy.eachDependency {
        val adventureArtifact = requested.group == "net.kyori" && requested.name.startsWith("adventure-")
                && requested.name != "adventure-bom"
        val keepDeclared = pinned.get() && configurationName == "compileClasspath"
        if (adventureArtifact && !keepDeclared) {
            useVersion(adventureVersion)
            because("-PadventureVersion=$adventureVersion")
        }
    }
}

dependencies {
    compileOnlyApi(lib("annotations"))
    testCompileOnly(lib("annotations"))
    testImplementation(platform(lib("junit-bom")))
    testImplementation(lib("junit-jupiter"))
    testRuntimeOnly(lib("junit-launcher"))
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    inputs.property("adventureVersion", adventureVersion)
    systemProperty("minimessageflow.test.adventureVersion", adventureVersion)
    testLogging {
        events("failed", "skipped")
        exceptionFormat = TestExceptionFormat.FULL
    }
}
