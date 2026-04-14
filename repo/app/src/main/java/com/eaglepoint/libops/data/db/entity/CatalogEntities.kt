package com.eaglepoint.libops.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "master_records",
    indices = [
        Index(value = ["isbn13"]),
        Index(value = ["titleNormalized"]),
        Index(value = ["isbn13", "titleNormalized"]),
        Index(value = ["status"]),
    ],
)
data class MasterRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val titleNormalized: String,
    val publisher: String?,
    val pubDate: Long?, // epoch millis; validation forbids future dates in phase 1
    val format: String?, // book, journal, other specific formats
    val category: String, // book, journal, other
    val isbn10: String?,
    val isbn13: String?,
    val language: String?,
    val notes: String?,
    val status: String, // draft, active, archived
    val sourceProvenanceJson: String?,
    val createdByUserId: Long,
    val createdAt: Long,
    val updatedAt: Long,
    val version: Int = 1,
)

@Entity(
    tableName = "master_record_versions",
    indices = [Index(value = ["recordId", "version"], unique = true)],
    foreignKeys = [
        ForeignKey(
            entity = MasterRecordEntity::class,
            parentColumns = ["id"],
            childColumns = ["recordId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class MasterRecordVersionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val recordId: Long,
    val version: Int,
    val snapshotJson: String,
    val editorUserId: Long,
    val changeSummary: String?,
    val createdAt: Long,
)

@Entity(
    tableName = "taxonomy_nodes",
    indices = [
        Index(value = ["parentId", "name"], unique = true),
        Index(value = ["archived"]),
    ],
    foreignKeys = [
        ForeignKey(
            entity = TaxonomyNodeEntity::class,
            parentColumns = ["id"],
            childColumns = ["parentId"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
)
data class TaxonomyNodeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val parentId: Long?,
    val name: String,
    val description: String?,
    val archived: Boolean = false,
    val createdAt: Long,
    val updatedAt: Long,
    val version: Int = 1,
)

@Entity(
    tableName = "record_taxonomy",
    primaryKeys = ["recordId", "taxonomyId"],
    indices = [Index(value = ["taxonomyId"])],
    foreignKeys = [
        ForeignKey(entity = MasterRecordEntity::class, parentColumns = ["id"], childColumns = ["recordId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = TaxonomyNodeEntity::class, parentColumns = ["id"], childColumns = ["taxonomyId"], onDelete = ForeignKey.CASCADE),
    ],
)
data class RecordTaxonomyEntity(
    val recordId: Long,
    val taxonomyId: Long,
    val primary: Boolean = false,
)

@Entity(
    tableName = "holding_copies",
    indices = [Index(value = ["masterRecordId"])],
    foreignKeys = [
        ForeignKey(
            entity = MasterRecordEntity::class,
            parentColumns = ["id"],
            childColumns = ["masterRecordId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class HoldingCopyEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val masterRecordId: Long,
    val location: String,
    val totalCount: Int,
    val availableCount: Int,
    val lastAdjustmentReason: String?,
    val lastAdjustmentUserId: Long?,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "barcodes",
    indices = [
        Index(value = ["code"], unique = true),
        Index(value = ["state"]),
        Index(value = ["masterRecordId"]),
        Index(value = ["holdingId"]),
    ],
    foreignKeys = [
        ForeignKey(
            entity = MasterRecordEntity::class,
            parentColumns = ["id"],
            childColumns = ["masterRecordId"],
            onDelete = ForeignKey.SET_NULL,
        ),
        ForeignKey(
            entity = HoldingCopyEntity::class,
            parentColumns = ["id"],
            childColumns = ["holdingId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
)
data class BarcodeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val code: String,
    val masterRecordId: Long?,
    val holdingId: Long?,
    val state: String, // available, assigned, suspended, retired, reserved_hold
    val assignedAt: Long?,
    val retiredAt: Long?,
    val reservedUntil: Long?,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "record_attachments",
    indices = [Index(value = ["masterRecordId"])],
    foreignKeys = [
        ForeignKey(
            entity = MasterRecordEntity::class,
            parentColumns = ["id"],
            childColumns = ["masterRecordId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class RecordAttachmentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val masterRecordId: Long,
    val kind: String, // cover, asset
    val localPath: String,
    val sizeBytes: Long,
    val createdAt: Long,
)

/**
 * Admin/cataloger-configurable metadata field definition (§9).
 *
 * Every record metadata field — including the core built-in fields
 * (title, publisher, isbn13, etc.) — is modeled as a row in this table.
 * Built-in fields have [system] = true and [entityColumn] set to the
 * corresponding [MasterRecordEntity] column name. User-created fields
 * have [system] = false and store values in [RecordCustomFieldEntity].
 *
 * The [fieldType] determines input rendering and validation:
 * - `text`: free-form string
 * - `number`: numeric input
 * - `date`: date picker (stored as epoch millis in the value)
 * - `select`: single choice from [optionsJson] (JSON array of strings)
 *
 * When [required] is true, the record edit/create UI enforces a non-blank value.
 * [displayOrder] controls the rendering sequence in the edit dialog.
 */
@Entity(
    tableName = "field_definitions",
    indices = [
        Index(value = ["fieldKey"], unique = true),
        Index(value = ["archived"]),
    ],
)
data class FieldDefinitionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val fieldKey: String,
    val label: String,
    val fieldType: String, // text, number, date, select
    val required: Boolean = false,
    val optionsJson: String? = null, // JSON array for select type
    val displayOrder: Int = 0,
    val archived: Boolean = false,
    val system: Boolean = false,
    val entityColumn: String? = null, // maps to MasterRecordEntity column for system fields
    val createdByUserId: Long,
    val createdAt: Long,
    val updatedAt: Long,
)

/**
 * Per-record custom field value, linked to a [FieldDefinitionEntity].
 *
 * Stores the actual value as a string regardless of [FieldDefinitionEntity.fieldType];
 * the UI/validation layer interprets the value according to the field definition.
 */
@Entity(
    tableName = "record_custom_fields",
    indices = [
        Index(value = ["masterRecordId", "fieldDefinitionId"], unique = true),
        Index(value = ["fieldDefinitionId"]),
    ],
    foreignKeys = [
        ForeignKey(
            entity = MasterRecordEntity::class,
            parentColumns = ["id"],
            childColumns = ["masterRecordId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = FieldDefinitionEntity::class,
            parentColumns = ["id"],
            childColumns = ["fieldDefinitionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class RecordCustomFieldEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val masterRecordId: Long,
    val fieldDefinitionId: Long,
    val value: String,
    val updatedAt: Long,
)
