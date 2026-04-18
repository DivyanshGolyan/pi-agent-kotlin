import org.jetbrains.dokka.gradle.DokkaExtension
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.Exec
import org.gradle.jvm.tasks.Jar
import org.gradle.plugins.signing.SigningExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

plugins {
    alias(libs.plugins.binary.compatibility.validator)
    alias(libs.plugins.dokka)
    alias(libs.plugins.kover)
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ktlint) apply false
    alias(libs.plugins.android.library) apply false
}

group = providers.gradleProperty("GROUP").get()
version = providers.gradleProperty("VERSION_NAME").get()

subprojects {
    group = rootProject.group
    version = rootProject.version
}

apiValidation {
    ignoredProjects += listOf("android-consumer")
}

subprojects {
    pluginManager.withPlugin("java-library") {
        extensions.configure<JavaPluginExtension> {
            toolchain {
                languageVersion.set(JavaLanguageVersion.of(17))
            }
            sourceCompatibility = JavaVersion.VERSION_11
            targetCompatibility = JavaVersion.VERSION_11
        }
    }

    pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
        extensions.configure<KotlinJvmProjectExtension> {
            explicitApi()
            jvmToolchain(17)
            compilerOptions {
                jvmTarget.set(JvmTarget.JVM_11)
                freeCompilerArgs.add("-Xjsr305=strict")
            }
        }

        tasks.withType<KotlinJvmCompile>().configureEach {
            compilerOptions {
                jvmTarget.set(JvmTarget.JVM_11)
                freeCompilerArgs.add("-Xjsr305=strict")
            }
        }
    }

    pluginManager.withPlugin("org.jlleitschuh.gradle.ktlint") {
        extensions.configure<org.jlleitschuh.gradle.ktlint.KtlintExtension> {
            version.set("1.6.0")
            android.set(false)
            outputToConsole.set(true)
            ignoreFailures.set(false)
        }
    }

    pluginManager.withPlugin("io.gitlab.arturbosch.detekt") {
        extensions.configure<io.gitlab.arturbosch.detekt.extensions.DetektExtension> {
            buildUponDefaultConfig = true
            allRules = false
            ignoreFailures = false
            config.setFrom(rootProject.file("config/detekt/detekt.yml"))
        }
    }

    pluginManager.withPlugin("org.jetbrains.dokka") {
        extensions.configure<DokkaExtension> {
            dokkaSourceSets.configureEach {
                val sourceRoot = project.file("src/main/kotlin")
                if (sourceRoot.exists()) {
                    sourceLink {
                        localDirectory.set(sourceRoot)
                        remoteUrl("${providers.gradleProperty("POM_URL").get()}/tree/main/${project.name}/src/main/kotlin")
                        remoteLineSuffix.set("#L")
                    }
                }
            }
        }
    }

    pluginManager.withPlugin("maven-publish") {
        extensions.configure<PublishingExtension> {
            publications.withType(MavenPublication::class.java).configureEach {
                pom {
                    name.set(project.name)
                    description.set(project.description ?: "Kotlin port of selected pi-mono packages.")
                    url.set(providers.gradleProperty("POM_URL"))
                    licenses {
                        license {
                            name.set(providers.gradleProperty("POM_LICENSE_NAME"))
                            url.set(providers.gradleProperty("POM_LICENSE_URL"))
                        }
                    }
                    developers {
                        developer {
                            id.set(providers.gradleProperty("POM_DEVELOPER_ID"))
                            name.set(providers.gradleProperty("POM_DEVELOPER_NAME"))
                            url.set(providers.gradleProperty("POM_DEVELOPER_URL"))
                        }
                    }
                    scm {
                        url.set(providers.gradleProperty("POM_SCM_URL"))
                        connection.set(providers.gradleProperty("POM_SCM_CONNECTION"))
                        developerConnection.set(providers.gradleProperty("POM_SCM_DEV_CONNECTION"))
                    }
                }
            }
        }
    }

    pluginManager.withPlugin("signing") {
        val signingKey = providers.gradleProperty("signingInMemoryKey").orNull
        val signingPassword = providers.gradleProperty("signingInMemoryKeyPassword").orNull
        val signingKeyId = providers.gradleProperty("signingInMemoryKeyId").orNull

        if (!signingKey.isNullOrBlank() && !signingPassword.isNullOrBlank()) {
            extensions.configure<SigningExtension> {
                useInMemoryPgpKeys(signingKeyId, signingKey, signingPassword)
                sign(extensions.getByType(PublishingExtension::class.java).publications)
            }
        }
    }
}

tasks.register("checkParityEnvironment") {
    group = "verification"
    description = "Checks that Node/npm parity tooling is installed."

    doLast {
        val nodeModules = rootProject.file("node_modules")
        require(nodeModules.exists()) {
            "Parity tooling is not installed. Run `npm ci` from ${rootProject.projectDir} first."
        }
        require(rootProject.file("package.json").exists()) {
            "Missing package.json required for parity tooling."
        }
    }
}

tasks.register<Exec>("refreshParityFixtures") {
    group = "verification"
    description = "Regenerates committed TS parity fixtures from the pinned upstream snapshot."
    dependsOn("checkParityEnvironment")
    workingDir = rootProject.projectDir
    commandLine("npm", "run", "refresh-parity-fixtures")
}

tasks.register("parityTest") {
    group = "verification"
    description = "Runs fixture-based TS vs Kotlin parity tests."
    dependsOn("checkParityEnvironment")
    dependsOn(":pi-ai-core:parityTest")
    dependsOn(":pi-agent-core:parityTest")
}

extensions.configure<DokkaExtension> {
    dokkaPublications.html {
        failOnWarning.set(false)
    }
}
