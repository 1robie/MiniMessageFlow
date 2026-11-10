pluginManagement {
    includeBuild("build-logic")
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
    }
}

rootProject.name = "MiniMessageFlow"

include(
    "api",
    "core",
    "compat:adventure-4",
    "compat:adventure-5",
    "minimessageflow",
    "testkit",
    "benchmarks",
)
