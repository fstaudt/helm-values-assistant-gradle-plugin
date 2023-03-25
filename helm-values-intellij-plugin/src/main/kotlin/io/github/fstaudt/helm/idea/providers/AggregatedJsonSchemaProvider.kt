package io.github.fstaudt.helm.idea.providers

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtil.findFileByIoFile
import com.intellij.openapi.vfs.VirtualFile
import com.jetbrains.jsonSchema.extension.JsonSchemaFileProvider
import com.jetbrains.jsonSchema.extension.SchemaType
import com.jetbrains.jsonSchema.impl.JsonSchemaVersion.SCHEMA_7
import io.github.fstaudt.helm.AGGREGATED_SCHEMA_FILE
import io.github.fstaudt.helm.idea.baseDir
import io.github.fstaudt.helm.idea.service.HelmChartService.Companion.JSON_SCHEMAS_DIR
import java.io.File

class AggregatedJsonSchemaProvider(project: Project, private val chartDir: File) : JsonSchemaFileProvider {
    private val jsonSchemaPath = "$JSON_SCHEMAS_DIR/${chartDir.name}/$AGGREGATED_SCHEMA_FILE"
    private val jsonSchemaFile = findFileByIoFile(File(project.baseDir(), jsonSchemaPath), false)
    override fun isAvailable(file: VirtualFile) = file.name == "values.yaml" && (file.parent?.name == chartDir.name)
    override fun getName() = "aggregation for chart ${chartDir.name}"
    override fun getSchemaFile() = jsonSchemaFile
    override fun getSchemaVersion() = SCHEMA_7
    override fun getSchemaType() = SchemaType.userSchema
}
