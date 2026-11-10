plugins {
    id("minimessageflow.java")
}

description = "MiniMessageFlow engine. Compiled against -PadventureVersion; version-specific Adventure only through AdventureBridge."

dependencies {
    api(project(":api"))

    // Paper provides Adventure.
    compileOnlyApi(libs.adventure.api)
    compileOnly(libs.adventure.minimessage)
    compileOnly(libs.adventure.serializer.plain)
    compileOnly(libs.adventure.serializer.legacy)

    testImplementation(libs.adventure.api)
    testImplementation(libs.adventure.minimessage)
    testImplementation(libs.adventure.serializer.plain)
    testImplementation(libs.adventure.serializer.legacy)
}
