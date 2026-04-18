import org.gradle.api.tasks.testing.Test
import org.gradle.jvm.tasks.Jar

plugins {
    `java-library`
    `maven-publish`
    signing
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kover)
    alias(libs.plugins.detekt)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.dokka)
}

description = "Kotlin port of the pi-ai core subset required for direct Anthropic API-key usage."

java {
    withSourcesJar()
    withJavadocJar()
}

tasks.named<Jar>("javadocJar") {
    dependsOn(tasks.named("dokkaGeneratePublicationHtml"))
    from(layout.buildDirectory.dir("dokka/html"))
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
            artifactId = "pi-ai-core"
        }
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

kover {
    reports {
        total {
            verify {
                rule {
                    minBound(60)
                }
            }
        }
    }
}
