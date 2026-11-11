plugins {
    id("minimessageflow.java")
    alias(libs.plugins.shadow)
    `maven-publish`
}

description = "The one artifact users depend on: api + core + every compat module in one jar."

dependencies {
    api(project(":api"))
    // MiniMessageFlow, the entry point, is in core: users compile against it.
    api(project(":core"))
    implementation(project(":compat:adventure-4"))
    implementation(project(":compat:adventure-5"))

    testImplementation(libs.adventure.api)
    testImplementation(libs.adventure.minimessage)
}

tasks.jar {
    archiveClassifier = "plain"
}

val ownGroup = group.toString()
tasks.shadowJar {
    archiveClassifier = ""
    // Only our own modules: Adventure and the annotations are compileOnly, so they are never in this jar.
    dependencies {
        include { it.moduleGroup == ownGroup }
    }
}

tasks.assemble {
    dependsOn(tasks.shadowJar)
}

// The sources of everything in the jar, for IDEs (this module has none of its own).
tasks.named<Jar>("sourcesJar") {
    from(rootProject.file("api/src/main/java"))
    from(rootProject.file("core/src/main/java"))
    from(rootProject.file("compat/adventure-4/src/main/java"))
    from(rootProject.file("compat/adventure-5/src/main/java"))
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

// JitPack runs publishToMavenLocal on a tag (jitpack.yml); ./gradlew publishToMavenLocal for local use.
publishing {
    publications {
        create<MavenPublication>("maven") {
            // JitPack serves com.github.<user>:<Repo>:<tag>: published under those names there (it sets JITPACK=true),
            // so the POM and Gradle metadata match what users ask for.
            if (providers.environmentVariable("JITPACK").orNull == "true") {
                groupId = "com.github.1robie"
                artifactId = "MiniMessageFlow"
            } else {
                artifactId = "minimessageflow"
            }
            from(components["shadow"])
            artifact(tasks.named("sourcesJar"))
            pom {
                name = "MiniMessageFlow"
                description = "A fast text formatter for Adventure: MiniMessage tags, legacy colour codes and " +
                        "placeholders compiled once and rendered in nanoseconds, on Adventure 4.17 to 5.x."
                url = "https://github.com/1robie/MiniMessageFlow"
                licenses {
                    license {
                        name = "MIT License"
                        url = "https://opensource.org/licenses/MIT"
                    }
                }
                developers {
                    developer {
                        id = "1robie"
                        name = "1robie"
                        url = "https://github.com/1robie"
                    }
                }
                scm {
                    url = "https://github.com/1robie/MiniMessageFlow"
                    connection = "scm:git:https://github.com/1robie/MiniMessageFlow.git"
                    developerConnection = "scm:git:ssh://git@github.com/1robie/MiniMessageFlow.git"
                }
            }
        }
    }
}
