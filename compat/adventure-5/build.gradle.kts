plugins {
    id("minimessageflow.java")
}

description = "AdventureBridge for Adventure 5.x, compiled against 5.0."

adventure {
    pinned = true
}

dependencies {
    compileOnly(project(":core"))
    compileOnly(libs.adventure5.api)

    testImplementation(project(":core"))
    testImplementation(libs.adventure.api)
}
