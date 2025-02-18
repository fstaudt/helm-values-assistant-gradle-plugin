package io.github.fstaudt.helm.gradle

import io.github.fstaudt.helm.HelmConstants.HELM_CHARTS_DIR
import io.github.fstaudt.helm.HelmConstants.HELM_CHART_FILE
import io.github.fstaudt.helm.HelmConstants.HELM_VALUES_FILE
import io.github.fstaudt.helm.JsonSchemaConstants.PATCH_AGGREGATED_SCHEMA_FILE
import io.github.fstaudt.helm.JsonSchemaConstants.PATCH_AGGREGATED_SCHEMA_YAML_FILE
import io.github.fstaudt.helm.JsonSchemaConstants.PATCH_VALUES_SCHEMA_FILE
import io.github.fstaudt.helm.JsonSchemaConstants.PATCH_VALUES_SCHEMA_YAML_FILE
import io.github.fstaudt.helm.gradle.HelmValuesExtension.Companion.EXTENSION
import io.github.fstaudt.helm.gradle.HelmValuesExtension.Companion.HELM_SOURCES_DIR
import io.github.fstaudt.helm.gradle.services.JsonMapper
import io.github.fstaudt.helm.gradle.services.YamlMapper
import io.github.fstaudt.helm.gradle.tasks.AggregateJsonSchema
import io.github.fstaudt.helm.gradle.tasks.AggregateJsonSchema.Companion.AGGREGATE_JSON_SCHEMA
import io.github.fstaudt.helm.gradle.tasks.DownloadJsonSchemas
import io.github.fstaudt.helm.gradle.tasks.DownloadJsonSchemas.Companion.DOWNLOAD_JSON_SCHEMAS
import io.github.fstaudt.helm.gradle.tasks.ExtractHelmDependencies
import io.github.fstaudt.helm.gradle.tasks.ExtractHelmDependencies.Companion.EXTRACT_HELM_DEPENDENCIES
import io.github.fstaudt.helm.gradle.tasks.GenerateJsonSchemas
import io.github.fstaudt.helm.gradle.tasks.GenerateJsonSchemas.Companion.GENERATE_JSON_SCHEMAS
import io.github.fstaudt.helm.gradle.tasks.PublishJsonSchemas
import io.github.fstaudt.helm.gradle.tasks.PublishJsonSchemas.Companion.PUBLISH_JSON_SCHEMAS
import io.github.fstaudt.helm.gradle.tasks.ValidateHelmValues
import io.github.fstaudt.helm.gradle.tasks.ValidateHelmValues.Companion.VALIDATE_HELM_VALUES
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.register
import java.io.File

class HelmValuesPlugin : Plugin<Project> {
    companion object {
        const val HELM_VALUES = "helm-values"
        const val SCHEMA_VERSION = "https://json-schema.org/draft/2020-12/schema"
    }

    override fun apply(project: Project) {
        with(project) {
            val extension = extensions.create(EXTENSION, HelmValuesExtension::class.java).apply {
                sourcesDir.convention(HELM_SOURCES_DIR)
            }

            fun Project.sourceFile(file: String) = extension.sourcesDir.map { File(File(projectDir, it), file) }

            val sharedYamlMapper = project.gradle.sharedServices.registerIfAbsent("yaml", YamlMapper::class.java) {}
            val sharedJsonMapper = project.gradle.sharedServices.registerIfAbsent("json", JsonMapper::class.java) {}
            val downloadJsonSchemas = tasks.register<DownloadJsonSchemas>(DOWNLOAD_JSON_SCHEMAS) {
                group = HELM_VALUES
                description = "Download JSON schemas of dependencies from JSON schema repositories"
                repositoryMappings.putAll(extension.repositoryMappings)
                chartFile.set(sourceFile(HELM_CHART_FILE))
                yamlMapper.set(sharedYamlMapper)
                usesService(sharedYamlMapper)
            }
            val extractHelmDependencies = tasks.register<ExtractHelmDependencies>(EXTRACT_HELM_DEPENDENCIES) {
                group = HELM_VALUES
                description = "Extract JSON schemas, values and chart metadata from chart dependencies"
                chartFile.set(sourceFile(HELM_CHART_FILE))
                chartsDir.set(sourceFile(HELM_CHARTS_DIR))
                yamlMapper.set(sharedYamlMapper)
                usesService(sharedYamlMapper)
            }
            val aggregateJsonSchema = tasks.register<AggregateJsonSchema>(AGGREGATE_JSON_SCHEMA) {
                group = HELM_VALUES
                description =
                    "Aggregate extracted and downloaded JSON schemas for assistance on Helm values in your IDE"
                repositoryMappings.set(extension.repositoryMappings)
                chartFile.set(sourceFile(HELM_CHART_FILE))
                valuesPatchFile.set(sourceFile(PATCH_VALUES_SCHEMA_FILE))
                valuesYamlPatchFile.set(sourceFile(PATCH_VALUES_SCHEMA_YAML_FILE))
                aggregatedValuesPatchFile.set(sourceFile(PATCH_AGGREGATED_SCHEMA_FILE))
                aggregatedValuesYamlPatchFile.set(sourceFile(PATCH_AGGREGATED_SCHEMA_YAML_FILE))
                yamlMapper.set(sharedYamlMapper)
                usesService(sharedYamlMapper)
                jsonMapper.set(sharedJsonMapper)
                usesService(sharedJsonMapper)
                dependsOn(downloadJsonSchemas, extractHelmDependencies)
            }
            val generateJsonSchemas = tasks.register<GenerateJsonSchemas>(GENERATE_JSON_SCHEMAS) {
                group = HELM_VALUES
                description = "Generate JSON schemas for publication to a repository of JSON schemas"
                publishedVersion.set(extension.publishedVersion)
                publicationRepository.set(extension.publicationRepository)
                repositoryMappings.set(extension.repositoryMappings)
                chartFile.set(sourceFile(HELM_CHART_FILE))
                valuesPatchFile.set(sourceFile(PATCH_VALUES_SCHEMA_FILE))
                valuesYamlPatchFile.set(sourceFile(PATCH_VALUES_SCHEMA_YAML_FILE))
                yamlMapper.set(sharedYamlMapper)
                usesService(sharedYamlMapper)
                jsonMapper.set(sharedJsonMapper)
                usesService(sharedJsonMapper)
            }
            tasks.register<PublishJsonSchemas>(PUBLISH_JSON_SCHEMAS) {
                group = HELM_VALUES
                description = "Publish generated JSON schemas to a repository of JSON schemas"
                publishedVersion.set(extension.publishedVersion)
                publicationRepository.set(extension.publicationRepository)
                repositoryMappings.set(extension.repositoryMappings)
                chartFile.set(sourceFile(HELM_CHART_FILE))
                yamlMapper.set(sharedYamlMapper)
                usesService(sharedYamlMapper)
                dependsOn(generateJsonSchemas)
            }
            tasks.register<ValidateHelmValues>(VALIDATE_HELM_VALUES) {
                group = HELM_VALUES
                description = "Validate Helm values against aggregated JSON schema"
                valuesFile.set(sourceFile(HELM_VALUES_FILE))
                yamlMapper.set(sharedYamlMapper)
                usesService(sharedYamlMapper)
                jsonMapper.set(sharedJsonMapper)
                usesService(sharedJsonMapper)
                dependsOn(aggregateJsonSchema)
            }
        }
    }
}
