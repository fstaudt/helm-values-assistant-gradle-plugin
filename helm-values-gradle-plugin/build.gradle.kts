@file:Suppress("UnstableApiUsage")

plugins {
    `java-gradle-plugin`
    `kotlin-dsl`
    id("com.gradle.plugin-publish") version "1.3.1"
}

kotlin {
    jvmToolchain(17)
}

val pluginVersion = "$version"
val pluginName = "helm-values"
gradlePlugin {
    website.set("https://github.com/fstaudt/helm-values")
    vcsUrl.set("https://github.com/fstaudt/helm-values")
    plugins {
        register(pluginName) {
            id = "io.github.fstaudt.$pluginName"
            displayName = "Helm values"
            description = "Generate JSON schemas to help writing values for Helm charts!"
            implementationClass = "$group.gradle.HelmValuesPlugin"
            version = pluginVersion
            tags.set(listOf("helm", "chart", "kubernetes", "json", "schema"))
        }
    }
}

dependencies {
    compileOnly(gradleKotlinDsl())
    api(kotlin("gradle-plugin"))
    api(projects.helmValuesShared)
    api("com.networknt", "json-schema-validator", "1.5.3")
}

tasks.validatePlugins {
    enableStricterValidation = true
}

val currentGradleVersion: String = GradleVersion.current().version
val additionalGradleVersions = listOf("8.4")
val testGradleVersion = "testGradleVersion"
val displayNameSuffix = "displayNameSuffix"
testing {
    suites {
        named<JvmTestSuite>("test") {
            useJUnitJupiter()
            dependencies {
                implementation(gradleTestKit())
                implementation(projects.helmValuesTest)
                runtimeOnly("org.junit.jupiter:junit-jupiter-engine")
            }
            targets {
                named("test") {
                    testTask {
                        systemProperties(testGradleVersion to currentGradleVersion, displayNameSuffix to "")
                    }
                }
                additionalGradleVersions.forEach { version ->
                    create("testWithGradle${version.replace(Regex("\\W"), "_")}") {
                        testTask {
                            systemProperties(testGradleVersion to version, displayNameSuffix to " - Gradle $version")
                            mustRunAfter(tasks.test)
                        }
                    }
                }
            }
        }
    }
}

