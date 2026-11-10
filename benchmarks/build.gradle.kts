plugins {
    id("minimessageflow.java")
    alias(libs.plugins.jmh)
}

description = "JMH benchmarks, run on demand: ./gradlew :benchmarks:jmh"

dependencies {
    jmh(project(":core"))
    jmh(libs.adventure.api)
    jmh(libs.adventure.minimessage)
    jmh(libs.adventure.serializer.plain)
    jmh(libs.annotations)
}

// JMH's generated code is not ours: no -Werror on it.
tasks.withType<JavaCompile>().matching { it.name == "jmhCompileGeneratedClasses" }.configureEach {
    options.compilerArgs.removeAll(listOf("-Werror"))
}

jmh {
    jmhVersion = libs.versions.jmh
    warmupIterations = 5
    iterations = 10
    fork = 2
    profilers = listOf("gc")
    // ./gradlew :benchmarks:jmh -Pjmh.includes=Scanner
    providers.gradleProperty("jmh.includes").orNull?.let { includes = listOf(it) }
}
