plugins {
    id("minimessageflow.java")
    alias(libs.plugins.jmh)
}

description = "JMH benchmarks, run on demand: ./gradlew :benchmarks:jmh"

dependencies {
    // What users depend on: core with every bridge.
    jmh(project(":minimessageflow"))
    jmh(libs.adventure.api)
    jmh(libs.adventure.minimessage)
    jmh(libs.adventure.serializer.plain)
    // RenderBenchmark's ...Send: the JSON a server sends.
    jmh(libs.adventure.serializer.gson)
    jmh(libs.annotations)
}

// The comparison with FastMiniMessage is opt-in, so a normal build never fetches a third-party artifact:
//   ./gradlew :benchmarks:jmh -PadventureVersion=5.2.0 -PfastMiniMessageVersion=<version> -Pjmh.includes=FastMiniMessage
// It needs Adventure 5.2+ (its own requirement).
val fastMiniMessageVersion = providers.gradleProperty("fastMiniMessageVersion").orNull
if (fastMiniMessageVersion != null) {
    sourceSets.named("jmh") { java.srcDir("src/jmhFastMiniMessage/java") }
    dependencies {
        jmh("io.github.neoramm:fastminimessage:$fastMiniMessageVersion")
    }
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
    // Shorter iterations for a quick comparison (JMH's default is 10 s each): -Pjmh.time=1s
    providers.gradleProperty("jmh.time").orNull?.let {
        warmup = it
        timeOnIteration = it
    }
    // Several threads on the same templates, to see contention: -Pjmh.threads=8
    providers.gradleProperty("jmh.threads").orNull?.let { threads = it.toInt() }
    // JVM arguments for the forks, e.g. components built through Adventure's API:
    //   -Pjmh.jvmArgs=-Dminimessageflow.directComponents=false
    providers.gradleProperty("jmh.jvmArgs").orNull?.let { jvmArgsAppend = it.split(" ") }
    // One result file per run, so a run does not replace the last: -Pjmh.results=baseline-4.17
    providers.gradleProperty("jmh.results").orNull?.let {
        resultFormat = "JSON"
        resultsFile = layout.buildDirectory.file("results/jmh/$it.json")
        humanOutputFile = layout.buildDirectory.file("results/jmh/$it.txt")
    }
}
