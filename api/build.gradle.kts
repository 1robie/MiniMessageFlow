plugins {
    id("minimessageflow.java")
}

description = "Public types of MiniMessageFlow: Placeholder, PlaceholderValue, modifiers."

dependencies {
    // Paper provides Adventure.
    compileOnlyApi(libs.adventure.api)
    compileOnly(libs.adventure.minimessage)
    compileOnly(libs.adventure.serializer.legacy)
    compileOnly(libs.adventure.serializer.plain)

    testImplementation(libs.adventure.api)
    testImplementation(libs.adventure.minimessage)
    testImplementation(libs.adventure.serializer.legacy)
    testImplementation(libs.adventure.serializer.plain)
}
