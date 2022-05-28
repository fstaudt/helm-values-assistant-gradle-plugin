package io.github.fstaudt.helm.tasks

import com.github.tomakehurst.wiremock.client.WireMock
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.notFound
import com.github.tomakehurst.wiremock.client.WireMock.ok
import com.github.tomakehurst.wiremock.client.WireMock.stubFor
import com.github.tomakehurst.wiremock.client.WireMock.unauthorized
import com.github.tomakehurst.wiremock.http.Body
import com.github.tomakehurst.wiremock.junit5.WireMockTest
import io.github.fstaudt.helm.TestProject
import io.github.fstaudt.helm.WITH_BUILD_CACHE
import io.github.fstaudt.helm.assertions.JsonFileAssert.Companion.assertThatJsonFile
import io.github.fstaudt.helm.buildDir
import io.github.fstaudt.helm.initBuildFile
import io.github.fstaudt.helm.initHelmChart
import io.github.fstaudt.helm.runTask
import io.github.fstaudt.helm.tasks.HelmDownloadJsonSchemas.Companion.DOWNLOADS
import io.github.fstaudt.helm.tasks.HelmDownloadJsonSchemas.Companion.HELM_DOWNLOAD_JSON_SCHEMAS
import io.github.fstaudt.helm.testProject
import org.assertj.core.api.Assertions.assertThat
import org.gradle.testkit.runner.TaskOutcome.SUCCESS
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File

@WireMockTest(httpPort = 1980)
class HelmDownloadJsonSchemasTest {
    private lateinit var testProject: TestProject
    private lateinit var chartsDownloadFolder: File
    private lateinit var protectedDownloadFolder: File

    companion object {
        const val REPOSITORY_URL = "http://localhost:1980"
        const val CHARTS_ID = "@mycharts"
        const val CHARTS_PATH = "charts"
        const val CHARTS_FOLDER = "mycharts"
        const val PROTECTED_ID = "@myProtectedCharts"
        const val PROTECTED_PATH = "protected"
        const val PROTECTED_FOLDER = "myprotectedcharts"
        const val THIRDPARTY_PATH = "thirdparty"
        const val EXTERNAL_SCHEMA = "external-json-schema"
        const val PROTECTED_SCHEMA = "protected-json-schema"
        const val REF_SCHEMA = "ref-json-schema"
        const val THIRDPARTY_SCHEMA = "thirdparty-json-schema"
        const val AUTHORIZATION = "Basic dXNlcjpwYXNzd29yZA=="
    }

    @BeforeEach
    fun `init test project`() {
        testProject = testProject()
        chartsDownloadFolder = File("${testProject.buildDir}/$DOWNLOADS/$CHARTS_FOLDER")
        protectedDownloadFolder = File("${testProject.buildDir}/$DOWNLOADS/$PROTECTED_FOLDER")
        testProject.initBuildFile {
            appendText(
                """
                helmValuesAssistant {
                  repositoryMappings = mapOf(
                    "$CHARTS_ID" to RepositoryMapping("$CHARTS_FOLDER", "$REPOSITORY_URL/$CHARTS_PATH"),
                    "$PROTECTED_ID" to RepositoryMapping("$PROTECTED_FOLDER", "$REPOSITORY_URL/$PROTECTED_PATH", "$AUTHORIZATION")
                  )
                }
            """.trimIndent()
            )
        }
    }

    @BeforeEach
    fun `init wiremock`() {
        WireMock.reset()
    }

    @AfterEach
    fun `cleanup test project`() {
        testProject.deleteRecursively()
    }

    @Test
    fun `helmDownloadJsonSchema should download JSON schemas of dependencies from JSON schema repository`() {
        stubForSchemaResource("$CHARTS_PATH/$EXTERNAL_SCHEMA/0.1.0/helm-values.json")
        stubForSchemaResource("$CHARTS_PATH/$EXTERNAL_SCHEMA/0.1.0/helm-global.json")
        testProject.initHelmChart {
            appendText(
                """
                dependencies:
                - name: $EXTERNAL_SCHEMA
                  version: 0.1.0
                  repository: "$CHARTS_ID"
                - name: unknown-json-schema
                  version: 0.1.0
                  repository: "@unknown"
                """.trimIndent()
            )
        }
        testProject.runTask(WITH_BUILD_CACHE, HELM_DOWNLOAD_JSON_SCHEMAS).also {
            assertThat(it.task(":$HELM_DOWNLOAD_JSON_SCHEMAS")!!.outcome).isEqualTo(SUCCESS)
            assertThatJsonFile("$chartsDownloadFolder/$CHARTS_PATH/$EXTERNAL_SCHEMA/0.1.0/helm-values.json").isFile
                .hasContent().node("\$id").isEqualTo("$EXTERNAL_SCHEMA/0.1.0/helm-values.json")
            assertThatJsonFile("$chartsDownloadFolder/$CHARTS_PATH/$EXTERNAL_SCHEMA/0.1.0/helm-global.json").isFile
                .hasContent().node("\$id").isEqualTo("$EXTERNAL_SCHEMA/0.1.0/helm-global.json")
        }
    }

    @Test
    fun `helmDownloadJsonSchema should download JSON schemas of dependencies from protected JSON schema repository`() {
        stubForProtectedSchemaResource("$PROTECTED_PATH/$PROTECTED_SCHEMA/0.1.0/helm-values.json")
        stubForProtectedSchemaResource("$PROTECTED_PATH/$PROTECTED_SCHEMA/0.1.0/helm-global.json")
        testProject.initHelmChart {
            appendText(
                """
                dependencies:
                - name: $PROTECTED_SCHEMA
                  version: 0.1.0
                  repository: "$PROTECTED_ID"
                """.trimIndent()
            )
        }
        testProject.runTask(WITH_BUILD_CACHE, HELM_DOWNLOAD_JSON_SCHEMAS).also {
            assertThat(it.task(":$HELM_DOWNLOAD_JSON_SCHEMAS")!!.outcome).isEqualTo(SUCCESS)
            assertThatJsonFile("$protectedDownloadFolder/$PROTECTED_PATH/$PROTECTED_SCHEMA/0.1.0/helm-values.json").isFile
                .hasContent().node("\$id").isEqualTo("$PROTECTED_SCHEMA/0.1.0/helm-values.json")
            assertThatJsonFile("$protectedDownloadFolder/$PROTECTED_PATH/$PROTECTED_SCHEMA/0.1.0/helm-global.json").isFile
                .hasContent().node("\$id").isEqualTo("$PROTECTED_SCHEMA/0.1.0/helm-global.json")
        }
    }

    @Test
    fun `helmDownloadJsonSchema should download referenced JSON schemas from JSON schema repository when $ref is relative`() {
        stubForSchemaResource("$CHARTS_PATH/$EXTERNAL_SCHEMA/0.1.1/helm-values.json")
        stubForSchemaResource("$CHARTS_PATH/$REF_SCHEMA/0.1.0/helm-values.json")
        stubForSchemaResource("$CHARTS_PATH/$REF_SCHEMA/0.1.0/helm-global.json")
        testProject.initHelmChart {
            appendText(
                """
                dependencies:
                - name: $EXTERNAL_SCHEMA
                  version: 0.1.1
                  repository: "$CHARTS_ID"
                """.trimIndent()
            )
        }
        testProject.runTask(WITH_BUILD_CACHE, HELM_DOWNLOAD_JSON_SCHEMAS).also {
            assertThat(it.task(":$HELM_DOWNLOAD_JSON_SCHEMAS")!!.outcome).isEqualTo(SUCCESS)
            assertThatJsonFile("$chartsDownloadFolder/$CHARTS_PATH/$EXTERNAL_SCHEMA/0.1.1/helm-values.json").isFile
                .hasContent().and(
                    { it.node("\$id").isEqualTo("$EXTERNAL_SCHEMA/0.1.1/helm-values.json") },
                    { it.node("properties.ref.\$ref").isEqualTo("../../$REF_SCHEMA/0.1.0/helm-values.json") },
                )
            assertThatJsonFile("$chartsDownloadFolder/$CHARTS_PATH/$REF_SCHEMA/0.1.0/helm-values.json").isFile
                .hasContent().and(
                    { it.node("\$id").isEqualTo("$REF_SCHEMA/0.1.0/helm-values.json") },
                    { it.node("properties.global.\$ref").isEqualTo("helm-global.json") },
                )
            assertThatJsonFile("$chartsDownloadFolder/$CHARTS_PATH/$REF_SCHEMA/0.1.0/helm-global.json").isFile
                .hasContent().node("\$id").isEqualTo("$REF_SCHEMA/0.1.0/helm-global.json")
        }
    }

    @Test
    fun `helmDownloadJsonSchema should download referenced JSON schemas from JSON schema repository when $ref is relative with fragment`() {
        stubForSchemaResource("$CHARTS_PATH/$EXTERNAL_SCHEMA/0.1.1/helm-values.json")
        stubForSchemaResource("$CHARTS_PATH/$REF_SCHEMA/0.1.1/helm-global.json")
        testProject.initHelmChart {
            appendText(
                """
                dependencies:
                - name: $EXTERNAL_SCHEMA
                  version: 0.1.1
                  repository: "$CHARTS_ID"
                """.trimIndent()
            )
        }
        testProject.runTask(WITH_BUILD_CACHE, HELM_DOWNLOAD_JSON_SCHEMAS).also {
            assertThat(it.task(":$HELM_DOWNLOAD_JSON_SCHEMAS")!!.outcome).isEqualTo(SUCCESS)
            assertThatJsonFile("$chartsDownloadFolder/$CHARTS_PATH/$EXTERNAL_SCHEMA/0.1.1/helm-values.json").isFile
                .hasContent().and(
                    { it.node("\$id").isEqualTo("$EXTERNAL_SCHEMA/0.1.1/helm-values.json") },
                    {
                        it.node("properties.global.properties.refs.\$ref")
                            .isEqualTo("../../$REF_SCHEMA/0.1.1/helm-global.json#/objects/refs")
                    },
                )
            assertThatJsonFile("$chartsDownloadFolder/$CHARTS_PATH/$REF_SCHEMA/0.1.1/helm-global.json").isFile
                .hasContent().node("\$id").isEqualTo("$REF_SCHEMA/0.1.1/helm-global.json")
        }
    }

    @Test
    fun `helmDownloadJsonSchema should download referenced JSON schemas from JSON schema repository when $ref is relative and protected`() {
        stubForSchemaResource("$CHARTS_PATH/$EXTERNAL_SCHEMA/0.1.1/helm-global.json")
        stubForProtectedSchemaResource("$PROTECTED_PATH/$PROTECTED_SCHEMA/0.1.1/helm-global.json")
        testProject.initHelmChart {
            appendText(
                """
                dependencies:
                - name: $EXTERNAL_SCHEMA
                  version: 0.1.1
                  repository: "$CHARTS_ID"
                """.trimIndent()
            )
        }
        testProject.runTask(WITH_BUILD_CACHE, HELM_DOWNLOAD_JSON_SCHEMAS).also {
            assertThat(it.task(":$HELM_DOWNLOAD_JSON_SCHEMAS")!!.outcome).isEqualTo(SUCCESS)
            assertThatJsonFile("$chartsDownloadFolder/$CHARTS_PATH/$EXTERNAL_SCHEMA/0.1.1/helm-global.json").isFile
                .hasContent().and(
                    { it.node("\$id").isEqualTo("$EXTERNAL_SCHEMA/0.1.1/helm-global.json") },
                    {
                        it.node("properties.configMaps.\$ref")
                            .isEqualTo("../../../$PROTECTED_PATH/$PROTECTED_SCHEMA/0.1.1/helm-global.json#/objects/configMaps")
                    },
                )
            assertThatJsonFile("$chartsDownloadFolder/$PROTECTED_PATH/$PROTECTED_SCHEMA/0.1.1/helm-global.json").isFile
                .hasContent().node("\$id").isEqualTo("$PROTECTED_SCHEMA/0.1.1/helm-global.json")
        }
    }

    @Test
    fun `helmDownloadJsonSchema should download JSON schemas and update $ref in downloaded schema when $ref is a full URI`() {
        stubForSchemaResource("$CHARTS_PATH/$EXTERNAL_SCHEMA/0.1.2/helm-global.json")
        stubForSchemaResource("$THIRDPARTY_PATH/$THIRDPARTY_SCHEMA/0.1.0/helm-global.json")
        testProject.initHelmChart {
            appendText(
                """
                dependencies:
                - name: external-json-schema
                  version: 0.1.2
                  repository: "$CHARTS_ID"
                """.trimIndent()
            )
        }
        testProject.runTask(WITH_BUILD_CACHE, HELM_DOWNLOAD_JSON_SCHEMAS).also {
            assertThat(it.task(":$HELM_DOWNLOAD_JSON_SCHEMAS")!!.outcome).isEqualTo(SUCCESS)
            val downloadFolder = "$chartsDownloadFolder/$CHARTS_PATH/$EXTERNAL_SCHEMA/0.1.2"
            assertThatJsonFile("$downloadFolder/helm-global.json").isFile
                .hasContent().and(
                    { it.node("\$id").isEqualTo("$EXTERNAL_SCHEMA/0.1.2/helm-global.json") },
                    { it.node("\$ref").isEqualTo("refs/$THIRDPARTY_PATH/$THIRDPARTY_SCHEMA/0.1.0/helm-global.json") },
                )
            assertThatJsonFile("$downloadFolder/refs/$THIRDPARTY_PATH/$THIRDPARTY_SCHEMA/0.1.0/helm-global.json").isFile
                .hasContent().node("\$id").isEqualTo("$THIRDPARTY_SCHEMA/0.1.0/helm-global.json")
        }
    }

    @Test
    fun `helmDownloadJsonSchema should download JSON schemas and update $ref in downloaded schema when $ref is a full protected URI`() {
        stubForSchemaResource("$CHARTS_PATH/$EXTERNAL_SCHEMA/0.1.2/helm-values.json")
        stubForProtectedSchemaResource("$PROTECTED_PATH/$PROTECTED_SCHEMA/0.1.1/helm-values.json")
        stubForProtectedSchemaResource("$PROTECTED_PATH/$PROTECTED_SCHEMA/0.1.1/helm-global.json")
        testProject.initHelmChart {
            appendText(
                """
                dependencies:
                - name: external-json-schema
                  version: 0.1.2
                  repository: "$CHARTS_ID"
                """.trimIndent()
            )
        }
        testProject.runTask(WITH_BUILD_CACHE, HELM_DOWNLOAD_JSON_SCHEMAS).also {
            assertThat(it.task(":$HELM_DOWNLOAD_JSON_SCHEMAS")!!.outcome).isEqualTo(SUCCESS)
            val downloadFolder = "$chartsDownloadFolder/$CHARTS_PATH/$EXTERNAL_SCHEMA/0.1.2"
            assertThatJsonFile("$downloadFolder/helm-values.json").isFile
                .hasContent().and(
                    { it.node("\$id").isEqualTo("$EXTERNAL_SCHEMA/0.1.2/helm-values.json") },
                    {
                        it.node("properties.protected.\$ref")
                            .isEqualTo("refs/$PROTECTED_PATH/$PROTECTED_SCHEMA/0.1.1/helm-values.json")
                    },
                )
            assertThatJsonFile("$downloadFolder/refs/$PROTECTED_PATH/$PROTECTED_SCHEMA/0.1.1/helm-values.json").isFile
                .hasContent().and(
                    { it.node("\$id").isEqualTo("$PROTECTED_SCHEMA/0.1.1/helm-values.json") },
                    { it.node("properties.global.\$ref").isEqualTo("helm-global.json") }
                )
            assertThatJsonFile("$downloadFolder/refs/$PROTECTED_PATH/$PROTECTED_SCHEMA/0.1.1/helm-global.json").isFile
                .hasContent().node("\$id").isEqualTo("$PROTECTED_SCHEMA/0.1.1/helm-global.json")
        }
    }

    @Test
    fun `helmDownloadJsonSchema should download JSON schemas and update $ref in downloaded schema when $ref is a full URI with fragment`() {
        stubForSchemaResource("$CHARTS_PATH/$EXTERNAL_SCHEMA/0.1.2/helm-values.json")
        stubForSchemaResource("$THIRDPARTY_PATH/$THIRDPARTY_SCHEMA/0.1.0/helm-values.json")
        stubForSchemaResource("$THIRDPARTY_PATH/$THIRDPARTY_SCHEMA/0.1.0/helm-global.json")
        testProject.initHelmChart {
            appendText(
                """
                dependencies:
                - name: external-json-schema
                  version: 0.1.2
                  repository: "$CHARTS_ID"
                """.trimIndent()
            )
        }
        testProject.runTask(WITH_BUILD_CACHE, HELM_DOWNLOAD_JSON_SCHEMAS).also {
            assertThat(it.task(":$HELM_DOWNLOAD_JSON_SCHEMAS")!!.outcome).isEqualTo(SUCCESS)
            val downloadFolder = "$chartsDownloadFolder/$CHARTS_PATH/$EXTERNAL_SCHEMA/0.1.2"
            assertThatJsonFile("$downloadFolder/helm-values.json").isFile
                .hasContent().and(
                    { it.node("\$id").isEqualTo("$EXTERNAL_SCHEMA/0.1.2/helm-values.json") },
                    {
                        it.node("properties.refs.\$ref")
                            .isEqualTo("refs/$THIRDPARTY_PATH/$THIRDPARTY_SCHEMA/0.1.0/helm-values.json#/objects/refs")
                    }
                )
            assertThatJsonFile("$downloadFolder/refs/$THIRDPARTY_PATH/$THIRDPARTY_SCHEMA/0.1.0/helm-values.json").isFile
                .hasContent().node("\$id").isEqualTo("$THIRDPARTY_SCHEMA/0.1.0/helm-values.json")
        }
    }

    @Test
    fun `helmDownloadJsonSchema should keep $ref with fragment`() {
        stubForSchemaResource("$CHARTS_PATH/$EXTERNAL_SCHEMA/0.1.3/helm-global.json")
        testProject.initHelmChart {
            appendText(
                """
                dependencies:
                - name: external-json-schema
                  version: 0.1.3
                  repository: "$CHARTS_ID"
                """.trimIndent()
            )
        }
        testProject.runTask(WITH_BUILD_CACHE, HELM_DOWNLOAD_JSON_SCHEMAS).also {
            assertThat(it.task(":$HELM_DOWNLOAD_JSON_SCHEMAS")!!.outcome).isEqualTo(SUCCESS)
            assertThatJsonFile("$chartsDownloadFolder/$CHARTS_PATH/$EXTERNAL_SCHEMA/0.1.3/helm-global.json").isFile
                .hasContent().and(
                    { it.node("\$id").isEqualTo("$EXTERNAL_SCHEMA/0.1.3/helm-global.json") },
                    { it.node("properties.configMaps.\$ref").isEqualTo("#/objects/configMaps") },
                )
        }
    }

    @Test
    fun `helmDownloadJsonSchema should generate JSON schema with error when JSON schema can't be downloaded from repository`() {
        stubFor(get("/$CHARTS_PATH/$EXTERNAL_SCHEMA/0.1.0/helm-values.json").willReturn(unauthorized()))
        stubFor(get("/$CHARTS_PATH/$EXTERNAL_SCHEMA/0.1.0/helm-global.json").willReturn(notFound()))
        testProject.initHelmChart {
            appendText(
                """
                dependencies:
                - name: external-json-schema
                  version: 0.1.0
                  repository: "$CHARTS_ID"
                """.trimIndent()
            )
        }
        testProject.runTask(WITH_BUILD_CACHE, HELM_DOWNLOAD_JSON_SCHEMAS).also { build ->
            assertThat(build.task(":$HELM_DOWNLOAD_JSON_SCHEMAS")!!.outcome).isEqualTo(SUCCESS)
            val baseUrl = "$REPOSITORY_URL/$CHARTS_PATH/$EXTERNAL_SCHEMA/0.1.0"
            assertThatJsonFile("$chartsDownloadFolder/$CHARTS_PATH/$EXTERNAL_SCHEMA/0.1.0/helm-values.json").isFile
                .hasContent().and(
                    { it.node("\$schema").isEqualTo("https://json-schema.org/draft/2020-12/schema") },
                    { it.node("\$id").isEqualTo("$baseUrl/helm-values.json") },
                    { it.node("type").isEqualTo("object") },
                    { it.node("\$error").isEqualTo("401 - Unauthorized") },
                )
            assertThatJsonFile("$chartsDownloadFolder/$CHARTS_PATH/$EXTERNAL_SCHEMA/0.1.0/helm-global.json").isFile
                .hasContent().and(
                    { it.node("\$schema").isEqualTo("https://json-schema.org/draft/2020-12/schema") },
                    { it.node("\$id").isEqualTo("$baseUrl/helm-global.json") },
                    { it.node("type").isEqualTo("object") },
                    { it.node("\$error").isEqualTo("404 - Not Found") },
                )
        }
    }

    @Test
    fun `helmDownloadJsonSchema should create empty unpack directory when chart has no dependencies`() {
        testProject.initHelmChart()
        testProject.runTask(HELM_DOWNLOAD_JSON_SCHEMAS).also {
            assertThat(it.task(":$HELM_DOWNLOAD_JSON_SCHEMAS")!!.outcome).isEqualTo(SUCCESS)
            assertThat(File("${testProject.buildDir}/$DOWNLOADS")).isEmptyDirectory
        }
    }

    private fun stubForSchemaResource(filePath: String) {
        stubFor(get("/$filePath").willReturn(ok().withResponseBody(bodyFrom(filePath))))
    }

    private fun stubForProtectedSchemaResource(filePath: String, authorizationHeader: String = AUTHORIZATION) {
        stubFor(
            get("/$filePath")
                .withHeader("Authorization", equalTo(authorizationHeader))
                .willReturn(ok().withResponseBody(bodyFrom(filePath)))
        )
    }

    private fun bodyFrom(file: String) = Body(File("src/test/resources/schemas/$file").readBytes())
}