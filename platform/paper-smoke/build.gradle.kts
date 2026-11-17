import java.time.Duration
import xyz.jpenilla.runpaper.task.RunServer

plugins {
    id("minimessageflow.java")
    alias(libs.plugins.shadow)
    alias(libs.plugins.run.paper)
}

description = "A test plugin run on real Paper and Folia servers (never published): ./gradlew :platform:paper-smoke:smoke"

dependencies {
    implementation(project(":platform:paper"))
    compileOnly(libs.paper.api)
    compileOnly(libs.placeholderapi) { isTransitive = false }
}

// Shaded and relocated as the README tells a plugin to: the smoke test also checks that relocating works.
val ownGroup = group.toString()
tasks.shadowJar {
    archiveClassifier = ""
    relocate("fr.robie.minimessageflow", "fr.robie.smoke.libs.minimessageflow")
    dependencies {
        exclude { it.moduleGroup != ownGroup }
    }
}

tasks.processResources {
    val pluginVersion = project.version.toString()
    inputs.property("version", pluginVersion)
    filesMatching("plugin.yml") {
        expand("version" to pluginVersion)
    }
}

/**
 * The servers the smoke test runs on: Paper and Folia, the lowest, the last 1.21 and the newest, each with the
 * plugins it has (PlaceholderAPI and MiniPlaceholders on Paper; Folia gets none: PlaceholderAPI does not support it).
 */
data class Server(val name: String, val minecraft: String, val folia: Boolean, val java: Int, val plugins: List<String>)

val placeholderApi = "https://hangarcdn.papermc.io/plugins/HelpChat/PlaceholderAPI/versions/2.12.3/PAPER/PlaceholderAPI-2.12.3.jar"
val miniPlaceholders2 = "https://cdn.modrinth.com/data/HQyibRsN/versions/wck4v0R0/MiniPlaceholders-Paper-2.3.0.jar"
val miniPlaceholders31 = "https://cdn.modrinth.com/data/HQyibRsN/versions/4zOT6txC/MiniPlaceholders-Paper-3.1.0.jar"
val miniPlaceholders32 = "https://cdn.modrinth.com/data/HQyibRsN/versions/N2WfJ0ll/MiniPlaceholders-Paper-3.2.0.jar"

val servers = listOf(
    Server("Paper1_20_6", "1.20.6", false, 21, listOf(placeholderApi, miniPlaceholders2)),
    Server("Paper1_21_11", "1.21.11", false, 21, listOf(placeholderApi, miniPlaceholders31)),
    Server("Paper26_3", "26.3", false, 25, listOf(placeholderApi, miniPlaceholders32)),
    Server("Folia1_20_6", "1.20.6", true, 21, listOf()),
    Server("Folia1_21_11", "1.21.11", true, 21, listOf()),
    Server("Folia26_3", "26.3", true, 25, listOf()),
)

// Only run when asked, with the Minecraft EULA accepted by whoever runs it: -Psmoke.eula=true.
val eula = providers.gradleProperty("smoke.eula").map { it.toBoolean() }.orElse(false)
val toolchains = extensions.getByType<JavaToolchainService>()

val smokeTasks = servers.map { server ->
    tasks.register<RunServer>("smoke${server.name}") {
        group = "smoke"
        description = "Runs the smoke test on ${if (server.folia) "Folia" else "Paper"} ${server.minecraft}."
        if (server.folia) {
            downloadsApiService.set(xyz.jpenilla.runtask.service.DownloadsAPIService.folia(project))
        }
        minecraftVersion(server.minecraft)
        pluginJars(tasks.shadowJar.flatMap { it.archiveFile })
        downloadPlugins {
            server.plugins.forEach { url(it) }
        }
        runDirectory(layout.buildDirectory.dir("smoke/${server.name}").get().asFile)
        javaLauncher = toolchains.launcherFor { languageVersion = JavaLanguageVersion.of(server.java) }
        // The plugin checks what it can without a player, writes its results, and stops the server.
        systemProperty("smoke.folia", server.folia)
        systemProperty("smoke.minecraft", server.minecraft)
        systemProperty("smoke.expectPlaceholderApi", server.plugins.contains(placeholderApi))
        systemProperty("smoke.expectMiniPlaceholders", server.plugins.any { it.contains("MiniPlaceholders") })
        // A server that does not stop does not block the build. Its exit code is not the result (a server can end
        // with 1 after saving everything): result.txt is, read by the check task that follows.
        timeout = Duration.ofMinutes(5)
        isIgnoreExitValue = true
        finalizedBy("check${name.removePrefix("smoke")}")
        val eulaAccepted = eula.get()
        jvmArgumentProviders.add(CommandLineArgumentProvider {
            check(eulaAccepted) {
                "A Minecraft server only starts once its EULA (https://aka.ms/MinecraftEULA) is accepted: run with -Psmoke.eula=true to accept it."
            }
            listOf("-Dcom.mojang.eula.agree=true")
        })
        // A fresh plugin folder each run, so the default files are copied from the jar again.
        val pluginFolder = layout.buildDirectory.dir("smoke/${server.name}/plugins/MiniMessageFlowSmoke").get().asFile
        doFirst {
            pluginFolder.deleteRecursively()
        }
    }
}

// Each run's result.txt: every check passed, or the build fails with the ones that did not.
servers.forEach { server ->
    val result = layout.buildDirectory.file("smoke/${server.name}/plugins/MiniMessageFlowSmoke/result.txt").get().asFile
    tasks.register("check${server.name}") {
        group = "smoke"
        description = "Reads the smoke test's result on ${server.name}."
        doLast {
            check(result.exists()) { "no result from ${server.name}: the plugin did not run (see the server's log)" }
            val lines = result.readLines()
            println(lines.joinToString("\n") { "  $it" })
            val failures = lines.filter { it.startsWith("FAIL") }
            check(failures.isEmpty()) { "${server.name}: ${failures.size} check(s) failed:\n" + failures.joinToString("\n") }
        }
    }
}

tasks.register("smoke") {
    group = "smoke"
    description = "Runs the smoke test on every server, one after another."
    dependsOn(smokeTasks)
}
smokeTasks.zipWithNext { earlier, later -> later.configure { mustRunAfter(earlier) } }
