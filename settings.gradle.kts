pluginManagement {
    includeBuild("build-logic")
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
        // Paper's API, for platform/paper (compileOnly: a server provides it).
        maven("https://repo.papermc.io/repository/maven-public/") {
            name = "papermc"
        }
        // PlaceholderAPI, for platform/paper (compileOnly: optional at runtime).
        maven("https://repo.extendedclip.com/releases/") {
            name = "placeholderapi"
            content { includeGroup("me.clip") }
        }
    }
}

rootProject.name = "MiniMessageFlow"

include(
    "api",
    "core",
    "compat:adventure-4",
    "compat:adventure-5",
    "minimessageflow",
    "platform:paper",
    "testkit",
    "benchmarks",
)
