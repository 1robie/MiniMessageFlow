plugins {
    id("minimessageflow.java")
}

description = "AdventureBridge for Adventure 4.17 - 4.x, compiled against 4.17."

adventure {
    pinned = true
}

dependencies {
    compileOnly(project(":core"))
    compileOnly(libs.adventure.api)

    testImplementation(project(":core"))
    testImplementation(libs.adventure.api)
}
