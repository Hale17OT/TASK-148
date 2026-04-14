package com.eaglepoint.libops.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.eaglepoint.libops.data.db.entity.BarcodeEntity
import com.eaglepoint.libops.data.db.entity.FieldDefinitionEntity
import com.eaglepoint.libops.data.db.entity.HoldingCopyEntity
import com.eaglepoint.libops.data.db.entity.MasterRecordEntity
import com.eaglepoint.libops.data.db.entity.MasterRecordVersionEntity
import com.eaglepoint.libops.data.db.entity.RecordAttachmentEntity
import com.eaglepoint.libops.data.db.entity.RecordCustomFieldEntity
import com.eaglepoint.libops.data.db.entity.RecordTaxonomyEntity
import com.eaglepoint.libops.data.db.entity.TaxonomyNodeEntity

@Dao
interface RecordDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(record: MasterRecordEntity): Long

    @Update
    suspend fun update(record: MasterRecordEntity): Int

    @Query("SELECT * FROM master_records WHERE id = :id LIMIT 1")
    suspend fun byId(id: Long): MasterRecordEntity?

    @Query("SELECT * FROM master_records WHERE isbn13 = :isbn13 LIMIT 1")
    suspend fun byIsbn13(isbn13: String): MasterRecordEntity?

    @Query(
        """
        SELECT * FROM master_records
        WHERE (titleNormalized LIKE :prefix || '%' OR publisher LIKE '%' || :q || '%')
        ORDER BY
          CASE WHEN isbn13 = :q THEN 0 ELSE 1 END,
          CASE WHEN titleNormalized = :prefix THEN 0 ELSE 1 END,
          CASE WHEN titleNormalized LIKE :prefix || '%' THEN 0 ELSE 1 END,
          titleNormalized ASC
        LIMIT :limit OFFSET :offset
        """
    )
    suspend fun search(prefix: String, q: String, limit: Int, offset: Int): List<MasterRecordEntity>

    @Query("SELECT COUNT(*) FROM master_records WHERE status = 'active'")
    suspend fun activeCount(): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertVersion(version: MasterRecordVersionEntity): Long

    @Query("SELECT * FROM master_record_versions WHERE recordId = :recordId ORDER BY version DESC")
    suspend fun versionsFor(recordId: Long): List<MasterRecordVersionEntity>
}

@Dao
interface TaxonomyDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(node: TaxonomyNodeEntity): Long

    @Update
    suspend fun update(node: TaxonomyNodeEntity): Int

    @Query("SELECT * FROM taxonomy_nodes WHERE id = :id LIMIT 1")
    suspend fun byId(id: Long): TaxonomyNodeEntity?

    @Query("SELECT * FROM taxonomy_nodes WHERE parentId IS :parentId ORDER BY name")
    suspend fun children(parentId: Long?): List<TaxonomyNodeEntity>

    @Query("SELECT COUNT(*) FROM taxonomy_nodes WHERE parentId = :id")
    suspend fun childCount(id: Long): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun assignToRecord(binding: RecordTaxonomyEntity)

    @Query("DELETE FROM record_taxonomy WHERE recordId = :recordId AND taxonomyId = :taxonomyId")
    suspend fun detachFromRecord(recordId: Long, taxonomyId: Long): Int
}

@Dao
interface HoldingDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(holding: HoldingCopyEntity): Long

    @Update
    suspend fun update(holding: HoldingCopyEntity): Int

    @Query("SELECT * FROM holding_copies WHERE masterRecordId = :recordId ORDER BY location")
    suspend fun forRecord(recordId: Long): List<HoldingCopyEntity>

    @Query("SELECT SUM(totalCount) FROM holding_copies WHERE masterRecordId = :recordId")
    suspend fun totalForRecord(recordId: Long): Int?
}

@Dao
interface BarcodeDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(barcode: BarcodeEntity): Long

    @Update
    suspend fun update(barcode: BarcodeEntity): Int

    @Query("SELECT * FROM barcodes WHERE code = :code LIMIT 1")
    suspend fun byCode(code: String): BarcodeEntity?

    @Query("SELECT * FROM barcodes WHERE holdingId = :holdingId")
    suspend fun forHolding(holdingId: Long): List<BarcodeEntity>

    @Query("SELECT COUNT(*) FROM barcodes WHERE code = :code")
    suspend fun countCode(code: String): Int
}

@Dao
interface FieldDefinitionDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(field: FieldDefinitionEntity): Long

    @Update
    suspend fun update(field: FieldDefinitionEntity): Int

    @Query("SELECT * FROM field_definitions WHERE id = :id LIMIT 1")
    suspend fun byId(id: Long): FieldDefinitionEntity?

    @Query("SELECT * FROM field_definitions WHERE archived = 0 ORDER BY displayOrder, label")
    suspend fun activeFields(): List<FieldDefinitionEntity>

    /** Archive a non-system field. Returns 0 if field is system-protected. */
    @Query("UPDATE field_definitions SET archived = :archived, updatedAt = :now WHERE id = :id AND system = 0")
    suspend fun setArchived(id: Long, archived: Boolean, now: Long): Int

    @Query("SELECT * FROM field_definitions ORDER BY displayOrder, label")
    suspend fun allFields(): List<FieldDefinitionEntity>
}

@Dao
interface RecordCustomFieldDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(field: RecordCustomFieldEntity): Long

    @Query("SELECT * FROM record_custom_fields WHERE masterRecordId = :recordId")
    suspend fun forRecord(recordId: Long): List<RecordCustomFieldEntity>

    @Query("DELETE FROM record_custom_fields WHERE masterRecordId = :recordId AND fieldDefinitionId = :fieldDefId")
    suspend fun delete(recordId: Long, fieldDefId: Long): Int
}

@Dao
interface AttachmentDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(attachment: RecordAttachmentEntity): Long

    @Query("SELECT * FROM record_attachments WHERE masterRecordId = :recordId")
    suspend fun forRecord(recordId: Long): List<RecordAttachmentEntity>
}
