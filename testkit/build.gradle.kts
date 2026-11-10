plugins {
    id("minimessageflow.java")
}

description = "Behaviour contract, reference formatter and component normaliser, shared by every engine's tests."

dependencies {
    api(project(":api"))
    api(libs.adventure.api)
    api(libs.adventure.minimessage)
    api(libs.adventure.serializer.plain)
    api(libs.adventure.serializer.legacy)
    api(platform(libs.junit.bom))
    api(libs.junit.jupiter)
}
