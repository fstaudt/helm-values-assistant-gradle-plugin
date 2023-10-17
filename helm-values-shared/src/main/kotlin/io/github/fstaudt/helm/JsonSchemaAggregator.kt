package io.github.fstaudt.helm

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.MissingNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.github.fge.jsonpatch.JsonPatch
import io.github.fstaudt.helm.JsonSchemaGenerator.Companion.GLOBAL_VALUES_DESCRIPTION
import io.github.fstaudt.helm.JsonSchemaGenerator.Companion.GLOBAL_VALUES_TITLE
import io.github.fstaudt.helm.Keywords.Companion.ADDITIONAL_PROPERTIES
import io.github.fstaudt.helm.Keywords.Companion.UNEVALUATED_PROPERTIES
import io.github.fstaudt.helm.ObjectNodeExtensions.Companion.allOf
import io.github.fstaudt.helm.ObjectNodeExtensions.Companion.global
import io.github.fstaudt.helm.ObjectNodeExtensions.Companion.props
import io.github.fstaudt.helm.aggregation.schema.DownloadedSchemaAggregator
import io.github.fstaudt.helm.aggregation.schema.ExtractedSchemaAggregator
import io.github.fstaudt.helm.aggregation.schema.LocalSchemaAggregator
import io.github.fstaudt.helm.model.Chart
import io.github.fstaudt.helm.model.ChartDependency
import io.github.fstaudt.helm.model.JsonSchemaRepository
import java.io.File
import java.net.URI

class JsonSchemaAggregator(
    private val repositoryMappings: Map<String, JsonSchemaRepository>,
    schemaLocator: SchemaLocator,
    chartDir: File,
    downloadedSchemasDir: File,
    extractedSchemasDir: File,
) {
    companion object {
        const val DEFS = "\$defs"
        const val BASE_URI = "https://helm-values.fstaudt.github.io"
        private val MISSING_NODE = MissingNode.getInstance()
    }

    private val generator = JsonSchemaGenerator(repositoryMappings, null)
    private val downloadedSchemaAggregator = DownloadedSchemaAggregator(repositoryMappings, downloadedSchemasDir)
    private val extractedSchemaAggregator = ExtractedSchemaAggregator(extractedSchemasDir)
    private val localSchemaAggregator = LocalSchemaAggregator(chartDir, schemaLocator)

    fun aggregate(chart: Chart, valuesJsonPatch: JsonPatch?, aggregatedJsonPatch: JsonPatch?): JsonNode {
        val jsonSchema = generator.generateValuesJsonSchema(chart, valuesJsonPatch)
        jsonSchema.put("\$id", "$BASE_URI/${chart.name}/${chart.version}/$AGGREGATED_SCHEMA_FILE")
        jsonSchema.put("title", "Configuration for chart ${chart.name}:${chart.version}")
        downloadedSchemaAggregator.aggregateFor(chart, jsonSchema)
        extractedSchemaAggregator.aggregateFor(jsonSchema)
        localSchemaAggregator.aggregateFor(chart, jsonSchema)
        jsonSchema.addGlobalPropertiesDescriptionFor(chart)
        jsonSchema.removeInvalidRefs()
        jsonSchema.put(UNEVALUATED_PROPERTIES, false)
        jsonSchema.put(ADDITIONAL_PROPERTIES, false)
        return aggregatedJsonPatch?.apply(jsonSchema) ?: jsonSchema
    }

    private fun ObjectNode.addGlobalPropertiesDescriptionFor(chart: Chart) {
        val dependencyLabels = chart.dependencies.joinToString("") { "$NEW_LINE- ${it.fullName()}" }
        val htmlDependencyLabels = chart.dependencies.joinToString("", "<ul>", "</ul>") {
            if (repositoryMappings.containsKey(it.repository)) {
                "<li><a href='${it.fullUri()}'>${it.fullName()}</a></li>"
            } else {
                "<li>${it.fullName()}</li>"
            }
        }
        props().global().allOf().also { allOf ->
            allOf.removeAll { it["title"]?.textValue()?.startsWith(GLOBAL_VALUES_TITLE) ?: false }
        }
        props().global().allOf().add(
            objectNode()
                .put("title", "$GLOBAL_VALUES_TITLE ${chart.name}:${chart.version}")
                .put("description", "$NEW_LINE $GLOBAL_VALUES_DESCRIPTION: $dependencyLabels")
                .put("x-intellij-html-description", "<br>$GLOBAL_VALUES_DESCRIPTION: $htmlDependencyLabels")
        )
    }

    private fun ChartDependency.fullUri() = repositoryMappings[repository]?.let { URI("${it.baseUri}/$name/$version") }

    private fun ChartDependency.fullName(): String {
        return if (isStoredLocally()) {
            "$name${version?.let { ":$it" } ?: ""}"
        } else {
            "${repository?.let { "$it/" } ?: ""}$name${version?.let { ":$it" } ?: ""}"
        }
    }

    private fun ObjectNode.removeInvalidRefs() {
        findParents("\$ref").forEach { parent ->
            val pointer = parent["\$ref"].textValue().removePrefix("#")
            if (runCatching { at(pointer) }.getOrDefault(MISSING_NODE).isMissingNode) {
                (parent as ObjectNode).put("_comment", "removed invalid \$ref ${parent["\$ref"].textValue()}")
                parent.remove("\$ref")
            }
        }
    }
}
