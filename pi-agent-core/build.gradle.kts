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

description = "Kotlin port of the pi-agent runtime on top of the scoped pi-ai port."

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
            artifactId = "pi-agent-core"
        }
    }
}

dependencies {
    api(project(":pi-ai-core"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.coroutines.test)
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
    description = "Runs pi-agent-core parity tests."
    testClassesDirs =
        sourceSets.test
            .get()
            .output.classesDirs
    classpath =
        sourceSets.test
            .get()
            .runtimeClasspath
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
                    minBound(70)
                }
            }
        }
    }
}
