package io.github.fstaudt.helm.tasks

import com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.KotlinModule
import io.github.fstaudt.helm.HelmValuesAssistantExtension
import io.github.fstaudt.helm.HelmValuesAssistantPlugin.Companion.HELM_VALUES
import io.github.fstaudt.helm.HelmValuesAssistantPlugin.Companion.SCHEMA_VERSION
import io.github.fstaudt.helm.model.Chart
import io.github.fstaudt.helm.model.ChartDependency
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.gradle.api.DefaultTask
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Nested
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity.RELATIVE
import org.gradle.api.tasks.TaskAction
import org.jetbrains.kotlin.gradle.internal.ensureParentDirsCreated
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.File

@CacheableTask
open class HelmUnpackJsonSchemas : DefaultTask() {
    companion object {
        const val HELM_UNPACK_JSON_SCHEMAS = "helmUnpackJsonSchemas"
        const val UNPACK = "$HELM_VALUES/unpack"
        const val SCHEMA_FILE = "values.schema.json"
    }

    private val logger: Logger = LoggerFactory.getLogger(HelmUnpackJsonSchemas::class.java)

    @OutputDirectory
    val unpackSchemasFolder = File(project.buildDir, UNPACK)

    @Nested
    lateinit var extension: HelmValuesAssistantExtension

    @InputDirectory
    @PathSensitive(RELATIVE)
    lateinit var chartsFolder: File

    @InputFile
    @PathSensitive(RELATIVE)
    lateinit var chartFile: File

    private val yamlMapper = ObjectMapper(YAMLFactory()).also {
        it.registerModule(KotlinModule.Builder().build())
        it.configure(FAIL_ON_UNKNOWN_PROPERTIES, false)
    }

    @TaskAction
    fun download() {
        unpackSchemasFolder.deleteRecursively()
        unpackSchemasFolder.mkdirs()
        val chart = chartFile.inputStream().use { yamlMapper.readValue(it, Chart::class.java) }
        chart.dependencies.forEach { dependency ->
            unpackSchema(dependency)
        }
    }

    private fun unpackSchema(dependency: ChartDependency) {
        val archive = File(chartsFolder,"${dependency.name}-${dependency.version}.tgz")
        if (archive.exists()) {
            try {
                archive.inputStream().use {
                    GzipCompressorInputStream(it).use {
                        TarArchiveInputStream(it).use {
                            var entry: TarArchiveEntry? = it.nextTarEntry
                            logger.info("entry ${entry?.name}: $entry")
                            while (entry != null && !entry.name.endsWith("/$SCHEMA_FILE")) {
                                logger.info("entry ${entry.name}: $entry")
                                entry = it.nextTarEntry
                            }
                            if (entry != null) {
                                with(dependency.toSchemaFileFor(entry)) {
                                    ensureParentDirsCreated()
                                    writeBytes(it.readAllBytes())
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                dependency.createErrorSchemaFileFor("${e.javaClass.name} - ${e.localizedMessage}")
            }
        } else {
            logger.warn("${dependency.name}:${dependency.version}: archive not found - skipping dependency.")
            logger.warn("Please run `helm dependency udpate` to download dependencies in charts folder.")
            dependency.createErrorSchemaFileFor("Archive not found")
        }
    }

    private fun ChartDependency.toSchemaFileFor(entry: TarArchiveEntry): File {
        val basePath = File("$unpackSchemasFolder/${alias ?: name}")
        return File(basePath, entry.name.removePrefix("${name}/").replace("charts/", ""))
    }

    private fun ChartDependency.createErrorSchemaFileFor(errorMessage: String) {
        File("$unpackSchemasFolder/${alias ?: name}/$SCHEMA_FILE").also {
            it.ensureParentDirsCreated()
            it.writeText(
                """
                    {
                      "${'$'}schema":"$SCHEMA_VERSION",
                      "${'$'}id":"$name/$version/$SCHEMA_FILE",
                      "type":"object",
                      "${'$'}error":"$errorMessage"
                    }
                """.trimIndent()
            )
        }
    }
}






