package com.eaglepoint.libops.data.db

import com.eaglepoint.libops.data.db.dao.FieldDefinitionDao
import com.eaglepoint.libops.data.db.entity.FieldDefinitionEntity

/**
 * Seeds built-in (system) field definitions on first run so that all
 * record metadata — including core fields like title, publisher, ISBN —
 * is driven from the configurable field model rather than hard-coded UI.
 *
 * System fields have [FieldDefinitionEntity.system] = true and
 * [FieldDefinitionEntity.entityColumn] pointing to the corresponding
 * [com.eaglepoint.libops.data.db.entity.MasterRecordEntity] column.
 * Admins/catalogers can adjust labels, display order, and required flags
 * but cannot archive or delete system fields.
 */
object FieldDefinitionSeeder {

    private data class BuiltIn(
        val key: String,
        val label: String,
        val type: String,
        val required: Boolean,
        val order: Int,
        val column: String,
    )

    private val BUILT_IN_FIELDS = listOf(
        BuiltIn("title", "Title", "text", required = true, order = 0, column = "title"),
        BuiltIn("publisher", "Publisher", "text", required = false, order = 1, column = "publisher"),
        BuiltIn("isbn13", "ISBN-13", "text", required = false, order = 2, column = "isbn13"),
        BuiltIn("isbn10", "ISBN-10", "text", required = false, order = 3, column = "isbn10"),
        BuiltIn("pub_date", "Publication Date", "date", required = false, order = 4, column = "pubDate"),
        BuiltIn("format", "Format", "text", required = false, order = 5, column = "format"),
        BuiltIn("category", "Category", "select", required = true, order = 6, column = "category"),
        BuiltIn("language", "Language", "text", required = false, order = 7, column = "language"),
        BuiltIn("notes", "Notes", "text", required = false, order = 8, column = "notes"),
    )

    suspend fun ensureSeeded(dao: FieldDefinitionDao, clock: () -> Long = { System.currentTimeMillis() }) {
        val existing = dao.allFields()
        val existingKeys = existing.map { it.fieldKey }.toSet()
        val now = clock()

        for (field in BUILT_IN_FIELDS) {
            if (field.key in existingKeys) continue
            dao.insert(
                FieldDefinitionEntity(
                    fieldKey = field.key,
                    label = field.label,
                    fieldType = field.type,
                    required = field.required,
                    displayOrder = field.order,
                    system = true,
                    entityColumn = field.column,
                    createdByUserId = 0,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
        }
    }
}
