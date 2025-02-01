import org.jetbrains.changelog.Changelog.OutputType.HTML
import org.jetbrains.changelog.markdownToHTML
import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType.IntellijIdeaCommunity
import org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform

val intellijPluginName: String by project
val intellijPluginVersion = "$version"
val intellijPluginSinceBuild: String by project
val intellijPluginUntilBuild: String by project
val intellijPlatformVersion: String by project
val intellijPlatformLatestVersion: String by project

plugins {
    // Java support
    id("java")
    // Kotlin support
    kotlin("jvm")
    // Gradle IntelliJ Plugin
    id("org.jetbrains.intellij.platform") version "2.2.1"
    // Gradle Changelog Plugin
    id("org.jetbrains.changelog") version "2.2.1"
}

repositories {
    intellijPlatform {
        defaultRepositories()
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(projects.helmValuesShared) {
        exclude(module = "slf4j-api")
    }
    api("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.18.2")
    intellijPlatform {
        intellijIdeaCommunity(intellijPlatformVersion)
        instrumentationTools()
        pluginVerifier()
        zipSigner()
        testFramework(Platform)
    }
    testImplementation("io.mockk:mockk:1.13.16")
    testImplementation("junit:junit:4.13.2")
    testImplementation(projects.helmValuesTest) {
        exclude("junit-jupiter-api")
        exclude("wiremock")
    }
    testRuntimeOnly("org.yaml:snakeyaml:2.3")
}

intellijPlatform {
    pluginConfiguration {
        name = intellijPluginName
        version = intellijPluginVersion
        description = pluginDescription()
        ideaVersion {
            sinceBuild = intellijPluginSinceBuild
            untilBuild = intellijPluginUntilBuild
        }
        // Get the latest available change notes from the changelog file
        changeNotes = provider {
            changelog.renderItem(changelog.run { getOrNull(intellijPluginVersion) ?: getLatest() }, HTML)
        }
    }
    publishing {
        token = System.getenv("PUBLISH_TOKEN")
        channels = listOf("default")
    }
    signing {
        certificateChain = System.getenv("CERTIFICATE_CHAIN")
        privateKey = System.getenv("PRIVATE_KEY")
        password = System.getenv("PRIVATE_KEY_PASSWORD")
    }
    pluginVerification {
        ides {
            recommended()
        }
    }
}

intellijPlatformTesting {
    runIde {
        register("runIdeLatest") {
            type = IntellijIdeaCommunity
            version = intellijPlatformLatestVersion
            useInstaller = false
        }
    }
}

changelog {
    version = intellijPluginVersion
    groups = listOf(
        "✨ New",
        "\uD83D\uDC1B Fixed",
        "\uD83D\uDD12 Security",
        "\uD83D\uDDD1 Deprecated",
        "\uD83D\uDD25 Removed"
    )
}

fun pluginDescription(): String {
    return projectDir.resolve("README.md").readText().lines().run {
        val start = "<!-- Plugin description -->"
        val end = "<!-- Plugin description end -->"
        if (!containsAll(listOf(start, end))) {
            throw GradleException("Plugin description section not found in README.md:\n$start\n...\n$end")
        }
        subList(indexOf(start) + 1, indexOf(end))
    }.joinToString("\n").run { markdownToHTML(this) }
}
