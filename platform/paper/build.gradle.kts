plugins {
    id("minimessageflow.java")
    `maven-publish`
}

description = "MiniMessageFlow on Paper 1.20.6+: PlaceholderAPI, player and server placeholders, messages from YAML."

// Compiled against the lowest Paper supported (libs.versions.paper), so it only uses what every later one has. CI
// compiles it against newer ones too: -PpaperVersion=1.21.11-R0.1-SNAPSHOT.
val paperVersion: String = providers.gradleProperty("paperVersion").orNull ?: libs.versions.paper.get()

// Paper 26 needs Java 25. The module is built for Java 21 (Paper 1.20.6 runs on it, and a Java 25 server runs it);
// only a compile check against Paper 26 (-PpaperVersion=26...) compiles it as Java 25.
if (paperVersion.substringBefore('.').toInt() >= 26) {
    java.toolchain.languageVersion = JavaLanguageVersion.of(25)
    tasks.withType<JavaCompile>().configureEach { options.release = 25 }
}

adventure {
    // Adventure comes with Paper: the compile classpath keeps Paper's, not -PadventureVersion.
    pinned = true
}

// Its tests run on Paper's Adventure too (a Paper server never runs another one): the version each artifact asks
// for, not -PadventureVersion, which is for core.
configurations.matching { it.name.startsWith("test") }.configureEach {
    resolutionStrategy.eachDependency {
        if (requested.group == "net.kyori" && requested.name.startsWith("adventure-") && requested.version != null) {
            useVersion(requested.version!!)
            because("platform/paper runs on the Adventure of its Paper")
        }
    }
}

// The tests check which version bridge the Paper they run on gets.
tasks.withType<Test>().configureEach {
    inputs.property("paperVersion", paperVersion)
    systemProperty("minimessageflow.test.paperVersion", paperVersion)
}

dependencies {
    api(project(":minimessageflow"))

    compileOnly("io.papermc.paper:paper-api:$paperVersion")
    compileOnly(libs.placeholderapi) { isTransitive = false }

    testImplementation("io.papermc.paper:paper-api:$paperVersion")
    testImplementation(libs.placeholderapi) { isTransitive = false }
    testImplementation(libs.miniplaceholders.api) { isTransitive = false }
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            // JitPack serves it as com.github.1robie.MiniMessageFlow:paper-minimessageflow:<tag>, from where it finds it
            // installed: under com.github.1robie, as the library (a JitPack build sets JITPACK=true).
            if (providers.environmentVariable("JITPACK").orNull == "true") {
                groupId = "com.github.1robie"
            }
            artifactId = "paper-minimessageflow"
            from(components["java"])
            pom {
                name = "MiniMessageFlow for Paper"
                description = project.description
                url = "https://github.com/1robie/MiniMessageFlow"
                licenses {
                    license {
                        name = "MIT License"
                        url = "https://opensource.org/licenses/MIT"
                    }
                }
            }
        }
    }
}
