plugins {
    kotlin("jvm") version embeddedKotlinVersion
    `java-library`
}

kotlin {
    jvmToolchain(11)
}

dependencies {
    api("org.junit.jupiter:junit-jupiter-api:5.9.3")
    api("org.assertj:assertj-core:3.24.2")
    api("net.javacrumbs.json-unit:json-unit-assertj:2.37.0") {
        api("net.minidev:json-smart:2.4.11")
    }
    api("com.github.tomakehurst:wiremock-jre8:2.35.0")
    implementation("org.jetbrains.kotlin:kotlin-stdlib")
    testRuntimeOnly("com.fasterxml.jackson.core:jackson-databind:2.14.2")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine")
}

tasks.test {
    useJUnitPlatform()
}
