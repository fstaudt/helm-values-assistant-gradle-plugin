package io.github.fstaudt.helm.idea.settings.ui

import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.CommonDataKeys.PROJECT
import com.intellij.openapi.options.BoundSearchableConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.COLUMNS_LARGE
import com.intellij.ui.dsl.builder.Cell
import com.intellij.ui.dsl.builder.LabelPosition
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.Row
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.gridLayout.HorizontalAlign
import com.intellij.ui.dsl.gridLayout.VerticalAlign
import com.intellij.util.ui.table.TableModelEditor
import io.github.fstaudt.helm.idea.HelmValuesBundle.message
import io.github.fstaudt.helm.idea.HelmValuesSettings
import io.github.fstaudt.helm.idea.HelmValuesSettings.Companion.HELM_BINARY
import io.github.fstaudt.helm.idea.settings.model.ChartRepository
import io.github.fstaudt.helm.idea.settings.model.JsonSchemaRepositoryMapping
import io.github.fstaudt.helm.idea.settings.service.ChartRepositoryService
import io.github.fstaudt.helm.idea.settings.service.JsonSchemaRepositoryMappingService
import javax.swing.JTable
import javax.swing.JTextField
import kotlin.reflect.KFunction
import kotlin.reflect.KMutableProperty0
import kotlin.reflect.KMutableProperty1

/**
 * Provides controller functionality for application settings.
 */
class HelmValuesConfigurable : BoundSearchableConfigurable(message("name"), "helm.values") {
    companion object {
        internal const val FIELD_REQUIRED = "settings.field.required"
    }

    private val state = HelmValuesSettings.instance.state
    private val chartRepositoryService = ChartRepositoryService.instance

    private val chartRepositoryEditor = TableModelEditor(
        arrayOf(
            Column("charts", ChartRepository::name, 40),
            Column("charts", ChartRepository::url, 150),
            BooleanColumn("charts", ChartRepository::secured, 50),
            BooleanColumn("charts", ChartRepository::synchronized, 80)
        ),
        ChartRepositoryEditor(),
        message("settings.charts.none")
    ).disableUpDownActions()
    private val jsonSchemaRepositoryMappingService = JsonSchemaRepositoryMappingService.instance
    private val jsonSchemaRepositoryMappingEditor = TableModelEditor(
        arrayOf(
            Column("mappings", JsonSchemaRepositoryMapping::name, 40),
            Column("mappings", JsonSchemaRepositoryMapping::baseUri, 150),
            Column("mappings", JsonSchemaRepositoryMapping::valuesSchemaFile, 50),
            Column("mappings", JsonSchemaRepositoryMapping::globalValuesSchemaFile, 60),
            BooleanColumn("mappings", JsonSchemaRepositoryMapping::secured, 50)
        ),
        JsonSchemaRepositoryMappingEditor(),
        message("settings.mappings.none")
    ).disableUpDownActions()
    private lateinit var helmBinaryPath: Cell<JBTextField>

    override fun createPanel(): DialogPanel {
        jsonSchemaRepositoryMappingEditor.reset(jsonSchemaRepositoryMappingService.list())
        return panel {
            rowWithTextFieldForProperty(state::helmBinaryPath) { cell ->
                cell.focused().also { helmBinaryPath = it }
            }
            row {
                cell(chartRepositoryEditor.createComponent())
                    .horizontalAlign(HorizontalAlign.FILL)
                    .verticalAlign(VerticalAlign.FILL)
                    .label(message("settings.charts.label"), LabelPosition.TOP)
            }.resizableRow()
            row {
                cell(jsonSchemaRepositoryMappingEditor.createComponent())
                    .horizontalAlign(HorizontalAlign.FILL)
                    .verticalAlign(VerticalAlign.FILL)
                    .label(message("settings.mappings.label"), LabelPosition.TOP)
            }.resizableRow()
        }
    }

    override fun isModified(): Boolean {
        return jsonSchemaRepositoryMappingEditor.model.items.sortedBy { it.name } != jsonSchemaRepositoryMappingService.list()
                || chartRepositoryEditor.model.items.sortedBy { it.name } != chartRepositoryService.list()
                || helmBinaryPath.component.text.trimOrElse(HELM_BINARY) != state.helmBinaryPath
    }

    override fun apply() {
        val project = PROJECT.getData(DataManager.getInstance().getDataContext(getPreferredFocusedComponent()))
        jsonSchemaRepositoryMappingService.update(jsonSchemaRepositoryMappingEditor.model.items)
        chartRepositoryService.update(project, chartRepositoryEditor.model.items)
        state.helmBinaryPath = helmBinaryPath.component.text.trimOrElse(HELM_BINARY)
        reset()
    }

    override fun reset() {
        jsonSchemaRepositoryMappingEditor.reset(jsonSchemaRepositoryMappingService.list())
        chartRepositoryEditor.reset(chartRepositoryService.list())
        helmBinaryPath.component.text = state.helmBinaryPath
    }

    private class Column<T, C>(
        private val tableName: String,
        private val field: KMutableProperty1<T, C>,
        private val preferredWidth: Int? = null,
    ) : TableModelEditor.EditableColumnInfo<T, C>() {
        override fun getName() = message("settings.$tableName.${field.name}.title")
        override fun getPreferredStringValue() = preferredWidth?.let { "".padEnd(it) }
        override fun valueOf(item: T): C = field.get(item)
        override fun setValue(item: T, value: C) {
            field.set(item, value)
        }

        override fun isCellEditable(item: T) = false
    }

    private class BooleanColumn<R>(
        private val tableName: String,
        private val function: KFunction<Boolean>,
        private val width: Int,
    ) : TableModelEditor.EditableColumnInfo<R, Boolean>() {
        override fun getColumnClass() = Boolean::class.java
        override fun getWidth(table: JTable) = width
        override fun getName() = message("settings.$tableName.${function.name}.title")
        override fun valueOf(item: R): Boolean = function.call(item)
        override fun isCellEditable(item: R) = false
    }

    private fun Panel.rowWithTextFieldForProperty(
        prop: KMutableProperty0<String>,
        textFieldFn: (Cell<JBTextField>) -> Cell<JBTextField> = { it },
    ): Row {
        return row(message("settings.${prop.name}.label")) {
            textField().forProperty(prop, textFieldFn)
        }
    }

    private fun <T : JTextField> Cell<T>.forProperty(
        prop: KMutableProperty0<String>,
        textFieldFn: (Cell<T>) -> Cell<T> = { it },
    ): Cell<T> {
        return accessibleName(prop.name)
            .bindText(prop)
            .columns(COLUMNS_LARGE)
            .comment(message("settings.${prop.name}.comment"))
            .let { textFieldFn(it) }
    }

    private fun String.trimOrElse(default: String) = trim().takeIf { it.isNotEmpty() } ?: default
}
