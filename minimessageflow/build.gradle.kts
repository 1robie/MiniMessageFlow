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

// Local only for now: ./gradlew publishToMavenLocal (Maven Central comes with the first release).
publishing {
    publications {
        create<MavenPublication>("maven") {
            artifactId = "minimessageflow"
            from(components["shadow"])
        }
    }
}
