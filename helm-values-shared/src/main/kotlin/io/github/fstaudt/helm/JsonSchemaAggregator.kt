package io.github.fstaudt.helm

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.MissingNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.databind.node.TextNode
import com.github.fge.jsonpatch.JsonPatch
import io.github.fstaudt.helm.JsonSchemaGenerator.Companion.GLOBAL_VALUES_DESCRIPTION
import io.github.fstaudt.helm.JsonSchemaGenerator.Companion.GLOBAL_VALUES_TITLE
import io.github.fstaudt.helm.Keywords.Companion.ADDITIONAL_PROPERTIES
import io.github.fstaudt.helm.Keywords.Companion.UNEVALUATED_PROPERTIES
import io.github.fstaudt.helm.ObjectNodeExtensions.Companion.allOf
import io.github.fstaudt.helm.ObjectNodeExtensions.Companion.global
import io.github.fstaudt.helm.ObjectNodeExtensions.Companion.globalOrNull
import io.github.fstaudt.helm.ObjectNodeExtensions.Companion.isInternalReference
import io.github.fstaudt.helm.ObjectNodeExtensions.Companion.objectNode
import io.github.fstaudt.helm.ObjectNodeExtensions.Companion.props
import io.github.fstaudt.helm.ObjectNodeExtensions.Companion.propsOrNull
import io.github.fstaudt.helm.ObjectNodeExtensions.Companion.toObjectNode
import io.github.fstaudt.helm.ObjectNodeExtensions.Companion.toUriFrom
import io.github.fstaudt.helm.model.Chart
import io.github.fstaudt.helm.model.ChartDependency
import io.github.fstaudt.helm.model.JsonSchemaRepository
import io.github.fstaudt.helm.model.RefMapping
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.File
import java.net.URI

class JsonSchemaAggregator(
    private val repositoryMappings: Map<String, JsonSchemaRepository>,
    private val schemaLocator: SchemaLocator,
    private val chartDir: File,
    private val downloadSchemasDir: File,
    private val extractSchemasDir: File,
) {
    companion object {
        const val DEFS = "\$defs"
        const val BASE_URI = "https://helm-values.fstaudt.github.io"
        const val EXTRACTED_GLOBAL_VALUES_TITLE = "Aggregated global values for"
        private val MISSING_NODE = MissingNode.getInstance()
        private val logger: Logger = LoggerFactory.getLogger(JsonSchemaAggregator::class.java)
    }

    private val generator = JsonSchemaGenerator(repositoryMappings, null)
    private val chartSchema = File(chartDir, HELM_SCHEMA_FILE)

    fun aggregate(chart: Chart, valuesJsonPatch: JsonPatch?, aggregatedJsonPatch: JsonPatch?): JsonNode {
        val jsonSchema = generator.generateValuesJsonSchema(chart, valuesJsonPatch)
        jsonSchema.put("\$id", "$BASE_URI/${chart.name}/${chart.version}/${AGGREGATED_SCHEMA_FILE}")
        jsonSchema.put("title", "Configuration for chart ${chart.name}:${chart.version}")
        jsonSchema.put(UNEVALUATED_PROPERTIES, false)
        jsonSchema.put(ADDITIONAL_PROPERTIES, false)
        jsonSchema.updateReferencesFor(chart.dependencies.toDownloadedRefMappings())
        jsonSchema.aggregateDownloadedSchemasFor(chart)
        jsonSchema.updateReferencesFor(chart.dependencies.toLocallyStoredRefMappings())
        jsonSchema.aggregateLocalSchemasFor(chart)
        jsonSchema.removeGlobalValuesSchemaRefsOfLocallyStoredDependenciesFor(chart)
        jsonSchema.removeGeneratedGlobalPropertiesDescription()
        jsonSchema.setExtractedDependencyReferencesFrom(extractSchemasDir,
            "#/$DEFS/${extractSchemasDir.name}",
            jsonSchema)
        jsonSchema.addGlobalPropertiesDescriptionFor(chart)
        chartSchema.takeIf { it.exists() }?.let {
            jsonSchema.allOf()
                .add(jsonSchema.objectNode().put("\$ref", "#/$DEFS/local/${chart.name}/$HELM_SCHEMA_FILE"))
            jsonSchema.aggregateChartSchemaFor(chart)
        }
        jsonSchema.removeInvalidRefs()
        return aggregatedJsonPatch?.apply(jsonSchema) ?: jsonSchema
    }

    private fun ObjectNode.removeGeneratedGlobalPropertiesDescription() {
        props().global().allOf().also { allOf ->
            allOf.removeAll { it["title"]?.textValue()?.startsWith(GLOBAL_VALUES_TITLE) ?: false }
        }
    }

    private fun ObjectNode.removeGlobalValuesSchemaRefsOfLocallyStoredDependenciesFor(chart: Chart) {
        val globalRefs = chart.dependencies.filter { it.isStoredLocally() }
            .map { "../../${it.name}/${it.sanitizedVersion()}/$GLOBAL_VALUES_SCHEMA_FILE" }
        props().global().allOf().removeAll { globalRefs.contains(it["\$ref"]?.textValue()) }
    }

    private fun ObjectNode.setExtractedDependencyReferencesFrom(
        schemasDir: File,
        refPrefix: String,
        jsonSchema: ObjectNode
    ) {
        schemasDir.listFiles { file -> file.isDirectory }?.forEach { file ->
            val ref = "$refPrefix/${file.name}"
            with(props().objectNode(file.name)) {
                if (file.containsFile(HELM_SCHEMA_FILE)) {
                    put("\$ref", "$ref/$HELM_SCHEMA_FILE")
                    jsonSchema.aggregateExtractedSchemaFor(file, "$ref/$HELM_SCHEMA_FILE".removePrefix("#/"))
                }
                setExtractedDependencyReferencesFrom(file, ref, jsonSchema)
                propsOrNull()?.globalOrNull()?.let {
                    addGlobalPropertiesDescriptionFor(ref.removePrefix("#/$DEFS/${extractSchemasDir.name}/"))
                }
                if (has("\$ref") && size() > 1) {
                    allOf().add(objectNode().set("\$ref", remove("\$ref")) as JsonNode)
                }
            }
            addGlobalPropertiesFrom(file, refPrefix)
        }
    }

    private fun ObjectNode.aggregateExtractedSchemaFor(schemaDir: File, schemaPath: String) {
        val schemaNode = schemaPath.split("/").filter { it.isNotBlank() }
            .fold(this) { node, s -> node.objectNode(s) }
        val schema = File(schemaDir, HELM_SCHEMA_FILE).toObjectNode()
        schema.updateReferencesFor(listOf(schemaPath.toInternalRefMapping()))
        schema.removeAdditionalAndUnevaluatedProperties()
        schemaNode.setAll<JsonNode>(schema)
    }

    private fun ObjectNode.addGlobalPropertiesFrom(schemasDir: File, refPrefix: String) {
        if (schemasDir.containsFile(HELM_SCHEMA_FILE)) {
            val ref = "$refPrefix/${schemasDir.name}/$HELM_SCHEMA_FILE/properties/global"
            props().global().allOf().add(objectNode().put("\$ref", ref))
        }
        if (schemasDir.hasSubDirectories()) {
            schemasDir.listFiles()?.forEach {
                addGlobalPropertiesFrom(it, "$refPrefix/${schemasDir.name}")
            }
        }
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
        props().global().allOf().add(
            objectNode()
                .put("title", "$GLOBAL_VALUES_TITLE ${chart.name}:${chart.version}")
                .put("description", "$NEW_LINE $GLOBAL_VALUES_DESCRIPTION: $dependencyLabels")
                .put("x-intellij-html-description", "<br>$GLOBAL_VALUES_DESCRIPTION: $htmlDependencyLabels")
        )
    }

    private fun ObjectNode.addGlobalPropertiesDescriptionFor(dependencyName: String) {
        props().global().allOf().add(
            objectNode()
                .put("title", "$EXTRACTED_GLOBAL_VALUES_TITLE $dependencyName dependency")
                .put("description", NEW_LINE)
        )
    }

    private fun ObjectNode.aggregateDownloadedSchemasFor(chart: Chart) {
        chart.dependencies.filter { repositoryMappings.contains(it.repository) }.forEach {
            aggregateDownloadedSchemaFor(it.valuesSchemaUri())
        }
    }

    private fun ChartDependency.valuesSchemaUri(): URI {
        return repositoryMappings[repository]!!.let { URI("${it.baseUri}/$name/$version/${it.valuesSchemaFile}") }
    }

    private fun ObjectNode.aggregateDownloadedSchemaFor(schemaUri: URI): String {
        val schemaNode = schemaUri.path.split("/").filter { it.isNotBlank() }
            .fold(objectNode(DEFS).objectNode(downloadSchemasDir.name)) { node, s -> node.objectNode(s) }
        val schemaPath = schemaUri.path.split("/").filter { it.isNotBlank() }
            .fold("#/$DEFS/${downloadSchemasDir.name}") { basePath, dir -> "$basePath/$dir" }
        if (schemaNode.isEmpty) {
            val schema = File(downloadSchemasDir, schemaUri.path).toObjectNode()
            schema.findParents("\$ref").forEach { parent ->
                val ref = parent["\$ref"]
                if (ref.isInternalReference()) {
                    val refMapping = RefMapping("#", schemaPath)
                    (parent as ObjectNode).replace("\$ref", refMapping.map(ref))
                } else {
                    try {
                        val refUri = ref.toUriFrom(schemaUri)
                        val refPath = aggregateDownloadedSchemaFor(refUri)
                        (parent as ObjectNode).replace("\$ref", TextNode(refPath + (refUri.fragment ?: "")))
                    } catch (e: Exception) {
                        logger.warn("Failed to aggregate schema for ref \"${ref.textValue()}\"", e)
                    }
                }
            }
            schema.removeAdditionalAndUnevaluatedProperties()
            schemaNode.setAll<JsonNode>(schema)
        }
        return schemaPath
    }

    private fun ObjectNode.aggregateLocalSchemasFor(chart: Chart) {
        chart.dependencies.filter { it.isStoredLocally() }.forEach {
            aggregateLocalSchema(it, schemaLocator.aggregatedSchemaFor(it))
        }
    }

    private fun ObjectNode.aggregateLocalSchema(dependency: ChartDependency, schemaFile: File) {
        val schemaNode = objectNode(DEFS).objectNode("local").objectNode(dependency.name).objectNode(schemaFile.name)
        val schemaPath = "#/$DEFS/local/${dependency.name}/${schemaFile.name}"
        val schema = schemaFile.toObjectNode()
        schema.findParents("\$ref").forEach { parent ->
            val ref = parent["\$ref"]
            val refMapping = RefMapping("#", schemaPath)
            (parent as ObjectNode).replace("\$ref", refMapping.map(ref))
        }
        schema.removeAdditionalAndUnevaluatedProperties()
        schemaNode.setAll<JsonNode>(schema)
    }

    private fun ObjectNode.aggregateChartSchemaFor(chart: Chart) {
        val schemaNode = objectNode(DEFS).objectNode("local").objectNode(chart.name).objectNode(HELM_SCHEMA_FILE)
        val schemaPath = "#/$DEFS/local/${chart.name}/$HELM_SCHEMA_FILE"
        val schema = chartSchema.toObjectNode()
        schema.findParents("\$ref").forEach { parent ->
            val ref = parent["\$ref"]
            val refMapping = RefMapping("#", schemaPath)
            (parent as ObjectNode).replace("\$ref", refMapping.map(ref))
        }
        schema.removeAdditionalAndUnevaluatedProperties()
        schemaNode.setAll<JsonNode>(schema)
    }

    private fun ChartDependency.fullUri() = repositoryMappings[repository]?.let { URI("${it.baseUri}/$name/$version") }

    private fun ChartDependency.fullName(): String {
        return if (isStoredLocally()) {
            "$name${version?.let { ":$it" } ?: ""}"
        } else {
            "${repository?.let { "$it/" } ?: ""}$name${version?.let { ":$it" } ?: ""}"
        }
    }

    private fun File.hasSubDirectories() = listFiles { file -> file.isDirectory }?.any() ?: false
    private fun File.containsFile(fileName: String) = listFiles { file -> file.name == fileName }?.any() ?: false

    private fun ObjectNode.updateReferencesFor(refMappings: List<RefMapping>) {
        findParents("\$ref").forEach { parent ->
            val ref = parent["\$ref"]
            refMappings.firstOrNull { it.matches(ref) }?.let {
                (parent as ObjectNode).replace("\$ref", it.map(ref))
            }
        }
    }

    private fun List<ChartDependency>.toDownloadedRefMappings() = mapNotNull { it.toDownloadedRefMapping() }
    private fun ChartDependency.toDownloadedRefMapping(): RefMapping? {
        return repositoryMappings[repository]?.let {
            RefMapping(
                "${it.baseUri}/$name/$version",
                "#/$DEFS/${downloadSchemasDir.name}${URI(it.baseUri).path}/$name/$version"
            )
        }
    }

    private fun List<ChartDependency>.toLocallyStoredRefMappings() = mapNotNull { it.toLocallyStoredRefMapping() }
    private fun ChartDependency.toLocallyStoredRefMapping(): RefMapping? {
        return takeIf { it.isStoredLocally() }?.let {
            RefMapping(
                "../../$name/${sanitizedVersion()}/$VALUES_SCHEMA_FILE",
                "#/$DEFS/local/$name/$AGGREGATED_SCHEMA_FILE")
        }
    }

    private fun String.toInternalRefMapping() = RefMapping("#", "#/$this")

    private fun ObjectNode.removeInvalidRefs() {
        findParents("\$ref").forEach { parent ->
            val pointer = parent["\$ref"].textValue().removePrefix("#")
            if (runCatching { at(pointer) }.getOrDefault(MISSING_NODE).isMissingNode) {
                (parent as ObjectNode).put("_comment", "removed invalid \$ref ${parent["\$ref"].textValue()}")
                parent.remove("\$ref")
            }
        }
    }

    private fun ObjectNode.removeAdditionalAndUnevaluatedProperties() {
        remove(ADDITIONAL_PROPERTIES)
        remove(UNEVALUATED_PROPERTIES)
        propsOrNull()?.globalOrNull()?.let {
            it.remove(ADDITIONAL_PROPERTIES)
            it.remove(UNEVALUATED_PROPERTIES)
        }
    }
}
