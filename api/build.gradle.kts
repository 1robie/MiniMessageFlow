plugins {
    id("minimessageflow.java")
}

description = "Public types of MiniMessageFlow: Placeholder, PlaceholderValue, modifiers."

dependencies {
    // Paper provides Adventure.
    compileOnlyApi(libs.adventure.api)
    compileOnly(libs.adventure.serializer.legacy)

    testImplementation(libs.adventure.api)
    testImplementation(libs.adventure.serializer.legacy)
}
