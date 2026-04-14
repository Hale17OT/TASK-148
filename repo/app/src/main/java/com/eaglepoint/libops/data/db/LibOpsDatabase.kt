package com.eaglepoint.libops.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.eaglepoint.libops.data.db.converters.AppConverters
import com.eaglepoint.libops.data.db.dao.AlertDao
import com.eaglepoint.libops.data.db.dao.AttachmentDao
import com.eaglepoint.libops.data.db.dao.AuditDao
import com.eaglepoint.libops.data.db.dao.BarcodeDao
import com.eaglepoint.libops.data.db.dao.CollectionSourceDao
import com.eaglepoint.libops.data.db.dao.FieldDefinitionDao
import com.eaglepoint.libops.data.db.dao.DuplicateDao
import com.eaglepoint.libops.data.db.dao.HoldingDao
import com.eaglepoint.libops.data.db.dao.ImportDao
import com.eaglepoint.libops.data.db.dao.JobDao
import com.eaglepoint.libops.data.db.dao.MetricsDao
import com.eaglepoint.libops.data.db.dao.PermissionDao
import com.eaglepoint.libops.data.db.dao.QualityScoreDao
import com.eaglepoint.libops.data.db.dao.RecordCustomFieldDao
import com.eaglepoint.libops.data.db.dao.RecordDao
import com.eaglepoint.libops.data.db.dao.SecretDao
import com.eaglepoint.libops.data.db.dao.SessionDao
import com.eaglepoint.libops.data.db.dao.TaxonomyDao
import com.eaglepoint.libops.data.db.dao.UserDao
import com.eaglepoint.libops.data.db.entity.AlertAcknowledgementEntity
import com.eaglepoint.libops.data.db.entity.AlertEntity
import com.eaglepoint.libops.data.db.entity.AlertResolutionEntity
import com.eaglepoint.libops.data.db.entity.AuditEventEntity
import com.eaglepoint.libops.data.db.entity.BarcodeEntity
import com.eaglepoint.libops.data.db.entity.CollectionSourceEntity
import com.eaglepoint.libops.data.db.entity.CrawlRuleEntity
import com.eaglepoint.libops.data.db.entity.DuplicateCandidateEntity
import com.eaglepoint.libops.data.db.entity.ExceptionEventEntity
import com.eaglepoint.libops.data.db.entity.FieldDefinitionEntity
import com.eaglepoint.libops.data.db.entity.HoldingCopyEntity
import com.eaglepoint.libops.data.db.entity.ImportBatchEntity
import com.eaglepoint.libops.data.db.entity.ImportRowResultEntity
import com.eaglepoint.libops.data.db.entity.ImportedBundleEntity
import com.eaglepoint.libops.data.db.entity.JobAttemptEntity
import com.eaglepoint.libops.data.db.entity.JobEntity
import com.eaglepoint.libops.data.db.entity.MasterRecordEntity
import com.eaglepoint.libops.data.db.entity.MasterRecordVersionEntity
import com.eaglepoint.libops.data.db.entity.MergeDecisionEntity
import com.eaglepoint.libops.data.db.entity.MetricSnapshotEntity
import com.eaglepoint.libops.data.db.entity.PerformanceSampleEntity
import com.eaglepoint.libops.data.db.entity.PermissionEntity
import com.eaglepoint.libops.data.db.entity.PolicyViolationEntity
import com.eaglepoint.libops.data.db.entity.QualityScoreSnapshotEntity
import com.eaglepoint.libops.data.db.entity.RecordAttachmentEntity
import com.eaglepoint.libops.data.db.entity.RecordCustomFieldEntity
import com.eaglepoint.libops.data.db.entity.RecordTaxonomyEntity
import com.eaglepoint.libops.data.db.entity.RoleEntity
import com.eaglepoint.libops.data.db.entity.RolePermissionEntity
import com.eaglepoint.libops.data.db.entity.SecretEntity
import com.eaglepoint.libops.data.db.entity.TaxonomyNodeEntity
import com.eaglepoint.libops.data.db.entity.UserEntity
import com.eaglepoint.libops.data.db.entity.UserRoleEntity
import com.eaglepoint.libops.data.db.entity.UserSessionEntity

@Database(
    entities = [
        // Identity
        UserEntity::class,
        RoleEntity::class,
        PermissionEntity::class,
        RolePermissionEntity::class,
        UserRoleEntity::class,
        UserSessionEntity::class,
        SecretEntity::class,
        // Audit
        AuditEventEntity::class,
        ExceptionEventEntity::class,
        PerformanceSampleEntity::class,
        PolicyViolationEntity::class,
        // Orchestration
        CollectionSourceEntity::class,
        CrawlRuleEntity::class,
        JobEntity::class,
        JobAttemptEntity::class,
        ImportedBundleEntity::class,
        ImportBatchEntity::class,
        ImportRowResultEntity::class,
        DuplicateCandidateEntity::class,
        MergeDecisionEntity::class,
        // Catalog
        MasterRecordEntity::class,
        MasterRecordVersionEntity::class,
        TaxonomyNodeEntity::class,
        RecordTaxonomyEntity::class,
        HoldingCopyEntity::class,
        BarcodeEntity::class,
        RecordAttachmentEntity::class,
        FieldDefinitionEntity::class,
        RecordCustomFieldEntity::class,
        // Observability
        AlertEntity::class,
        AlertAcknowledgementEntity::class,
        AlertResolutionEntity::class,
        MetricSnapshotEntity::class,
        QualityScoreSnapshotEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
@TypeConverters(AppConverters::class)
abstract class LibOpsDatabase : RoomDatabase() {
    abstract fun userDao(): UserDao
    abstract fun sessionDao(): SessionDao
    abstract fun permissionDao(): PermissionDao
    abstract fun secretDao(): SecretDao
    abstract fun auditDao(): AuditDao
    abstract fun collectionSourceDao(): CollectionSourceDao
    abstract fun jobDao(): JobDao
    abstract fun importDao(): ImportDao
    abstract fun duplicateDao(): DuplicateDao
    abstract fun recordDao(): RecordDao
    abstract fun taxonomyDao(): TaxonomyDao
    abstract fun holdingDao(): HoldingDao
    abstract fun barcodeDao(): BarcodeDao
    abstract fun fieldDefinitionDao(): FieldDefinitionDao
    abstract fun recordCustomFieldDao(): RecordCustomFieldDao
    abstract fun attachmentDao(): AttachmentDao
    abstract fun alertDao(): AlertDao
    abstract fun metricsDao(): MetricsDao
    abstract fun qualityScoreDao(): QualityScoreDao

    companion object {
        private const val DB_NAME = "libops.db"

        @Volatile private var instance: LibOpsDatabase? = null

        fun get(context: Context): LibOpsDatabase = instance ?: synchronized(this) {
            instance ?: build(context).also { instance = it }
        }

        private fun build(context: Context): LibOpsDatabase =
            Room.databaseBuilder(context.applicationContext, LibOpsDatabase::class.java, DB_NAME)
                .fallbackToDestructiveMigrationOnDowngrade()
                .build()

        fun inMemory(context: Context): LibOpsDatabase =
            Room.inMemoryDatabaseBuilder(context, LibOpsDatabase::class.java)
                .allowMainThreadQueries()
                .build()
    }
}
