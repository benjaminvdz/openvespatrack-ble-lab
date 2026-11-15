plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}
kotlin {
    jvmToolchain(17)
}
dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation(kotlin("test"))
}
tasks.test {
    useJUnit()
    testLogging { events("failed", "skipped") }
}
