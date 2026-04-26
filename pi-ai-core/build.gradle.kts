import org.gradle.api.tasks.testing.Test

plugins {
    `java-library`
    alias(libs.plugins.maven.publish)
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kover)
    alias(libs.plugins.detekt)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.dokka)
}

description = "Kotlin port of the pi-ai core subset required for direct Anthropic API-key usage."

mavenPublishing {
    coordinates(providers.gradleProperty("GROUP").get(), "pi-ai-core", providers.gradleProperty("VERSION_NAME").get())
    publishToMavenCentral()
    pom {
        name.set(project.name)
        description.set(project.description ?: "Kotlin port of selected pi-mono packages.")
    }
    if (!providers.gradleProperty("signingInMemoryKey").orNull.isNullOrBlank()) {
        signAllPublications()
    }
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockwebserver3)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    useJUnitPlatform {
        excludeTags("parity")
    }
}

tasks.register<Test>("parityTest") {
    group = "verification"
    description = "Runs pi-ai-core parity tests."
    testClassesDirs =
        sourceSets.test
            .get()
            .output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    systemProperty("parity.rootDir", rootProject.projectDir.absolutePath)
    useJUnitPlatform {
        includeTags("parity")
    }
}
