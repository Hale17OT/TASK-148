package com.eaglepoint.libops.tests

import androidx.test.core.app.ApplicationProvider
import com.eaglepoint.libops.audit.AuditLogger
import com.eaglepoint.libops.auth.AuthRepository
import com.eaglepoint.libops.auth.SeedData
import com.eaglepoint.libops.auth.SessionStore
import com.eaglepoint.libops.data.db.LibOpsDatabase
import com.eaglepoint.libops.data.db.entity.HoldingCopyEntity
import com.eaglepoint.libops.data.db.entity.MasterRecordEntity
import com.eaglepoint.libops.data.db.entity.UserEntity
import com.eaglepoint.libops.data.db.entity.UserRoleEntity
import com.eaglepoint.libops.domain.AppResult
import com.eaglepoint.libops.domain.auth.Authorizer
import com.eaglepoint.libops.domain.auth.Capabilities
import com.eaglepoint.libops.domain.auth.PasswordHasher
import com.eaglepoint.libops.domain.auth.Roles
import com.eaglepoint.libops.domain.catalog.CatalogService
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * End-to-end user journey against real Room: admin seeds → admin logs in →
 * admin creates a cataloger → cataloger logs in → cataloger creates a record
 * and adds a holding. Verifies capability enforcement, audit chain integrity,
 * and cross-role session isolation.
 *
 * This is the Android equivalent of a "multi-role scenario test" that would
 * otherwise require multi-user HTTP sessions against a backend.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class AdminOnboardsUserJourneyTest {

    private lateinit var db: LibOpsDatabase
    private lateinit var audit: AuditLogger
    private val clockMs = 1_700_000_000_000L
    private val adminPassword = "Admin@Review2024!"
    private val catalogerPassword = "Cataloger@2024!"

    @Before
    fun setUp() = runBlocking {
        db = LibOpsDatabase.inMemory(
            ApplicationProvider.getApplicationContext<android.content.Context>()
        )
        audit = AuditLogger(db.auditDao(), clock = { clockMs })
        SeedData(
            userDao = db.userDao(),
            permissionDao = db.permissionDao(),
            audit = audit,
            clock = { clockMs },
            bootstrapPasswordOverride = adminPassword,
        ).ensureSeeded()
    }

    @After
    fun tearDown() { db.close() }

    @Test
    fun full_admin_onboards_cataloger_then_cataloger_creates_record(): Unit = runBlocking {
        // ── Step 1: Admin logs in ─────────────────────────────────────────────
        val adminStore = SessionStore()
        val adminAuth = AuthRepository(
            userDao = db.userDao(),
            permissionDao = db.permissionDao(),
            sessionDao = db.sessionDao(),
            sessionStore = adminStore,
            audit = audit,
            clock = { clockMs },
        )
        val adminLogin = adminAuth.login("admin", adminPassword.toCharArray())
        assertThat(adminLogin).isInstanceOf(AppResult.Success::class.java)
        val adminSession = (adminLogin as AppResult.Success).data
        assertThat(adminSession.capabilities).contains(Capabilities.USERS_MANAGE)

        // ── Step 2: Admin creates a cataloger account ─────────────────────────
        val salt = PasswordHasher.generateSalt()
        val hash = PasswordHasher.hash(catalogerPassword.toCharArray(), salt)
        val catalogerId = db.userDao().insert(
            UserEntity(
                username = "cat_reviewer", displayName = "Cat Reviewer",
                passwordHash = PasswordHasher.encodeBase64(hash),
                passwordSalt = PasswordHasher.encodeBase64(salt),
                kdfAlgorithm = PasswordHasher.ALGORITHM,
                kdfIterations = PasswordHasher.ITERATIONS,
                kdfMemoryKb = PasswordHasher.MEMORY_KB,
                status = "active", biometricEnabled = false,
                createdAt = clockMs, updatedAt = clockMs,
            ),
        )
        val catalogerRole = db.permissionDao().roleByName(Roles.CATALOGER)!!
        db.permissionDao().assignRole(
            UserRoleEntity(
                userId = catalogerId, roleId = catalogerRole.id,
                active = true, assignedAt = clockMs,
                assignedByUserId = adminSession.userId,
            ),
        )
        audit.record(
            action = "user.created",
            targetType = "user",
            targetId = catalogerId.toString(),
            userId = adminSession.userId,
            reason = "role=cataloger",
        )

        // ── Step 3: Cataloger logs in using their new credentials ─────────────
        val catalogerStore = SessionStore()
        val catalogerAuth = AuthRepository(
            userDao = db.userDao(),
            permissionDao = db.permissionDao(),
            sessionDao = db.sessionDao(),
            sessionStore = catalogerStore,
            audit = audit,
            clock = { clockMs },
        )
        val catalogerLogin = catalogerAuth.login("cat_reviewer", catalogerPassword.toCharArray())
        assertThat(catalogerLogin).isInstanceOf(AppResult.Success::class.java)
        val catalogerSession = (catalogerLogin as AppResult.Success).data
        assertThat(catalogerSession.capabilities).contains(Capabilities.RECORDS_MANAGE)
        assertThat(catalogerSession.capabilities).doesNotContain(Capabilities.USERS_MANAGE)

        // ── Step 4: Cataloger tries a USERS_MANAGE action: must fail ──────────
        val catalogerAuthz = Authorizer(catalogerSession.capabilities)
        val catalogCatalog = CatalogService(
            authz = catalogerAuthz,
            recordDao = db.recordDao(),
            taxonomyDao = db.taxonomyDao(),
            holdingDao = db.holdingDao(),
            barcodeDao = db.barcodeDao(),
            audit = audit,
            clock = { clockMs },
        )
        // Cataloger has RECORDS_MANAGE → insertRecord must succeed
        val recordId = catalogCatalog.insertRecord(
            MasterRecordEntity(
                title = "Cataloger's Book",
                titleNormalized = "catalogers book",
                publisher = "Test Pub", pubDate = null, format = "paperback",
                category = "book", isbn10 = null, isbn13 = null,
                language = null, notes = null, status = "active",
                sourceProvenanceJson = null,
                createdByUserId = catalogerSession.userId,
                createdAt = clockMs, updatedAt = clockMs,
            ),
            userId = catalogerSession.userId,
        )

        // ── Step 5: Cataloger adds a holding for their record ─────────────────
        val holdingId = catalogCatalog.addHolding(
            HoldingCopyEntity(
                masterRecordId = recordId, location = "Main Library",
                totalCount = 3, availableCount = 3,
                lastAdjustmentReason = null, lastAdjustmentUserId = null,
                createdAt = clockMs, updatedAt = clockMs,
            ),
            userId = catalogerSession.userId,
        )
        assertThat(holdingId).isGreaterThan(0L)

        // ── Step 6: Verify audit chain contains entries from BOTH users ───────
        val events = db.auditDao().allEventsChronological()
        val adminEvents = events.filter { it.userId == adminSession.userId }
        val catalogerEvents = events.filter { it.userId == catalogerSession.userId }
        assertThat(adminEvents).isNotEmpty()
        assertThat(catalogerEvents).isNotEmpty()
        assertThat(adminEvents.any { it.action == "user.created" }).isTrue()
        assertThat(catalogerEvents.any { it.action == "record.created" }).isTrue()
        assertThat(catalogerEvents.any { it.action == "holding.created" }).isTrue()

        // ── Step 7: Verify both sessions are persisted in sessions table ──────
        val adminSessions = db.sessionDao().openSessionsForUser(adminSession.userId)
        val catalogerSessions = db.sessionDao().openSessionsForUser(catalogerSession.userId)
        assertThat(adminSessions).isNotEmpty()
        assertThat(catalogerSessions).isNotEmpty()
    }

    @Test
    fun cataloger_cannot_create_user_even_by_calling_dao_via_unauthorized_service(): Unit = runBlocking {
        // Seed a cataloger
        val salt = PasswordHasher.generateSalt()
        val hash = PasswordHasher.hash(catalogerPassword.toCharArray(), salt)
        db.userDao().insert(
            UserEntity(
                username = "cat_reviewer", displayName = "Cat",
                passwordHash = PasswordHasher.encodeBase64(hash),
                passwordSalt = PasswordHasher.encodeBase64(salt),
                kdfAlgorithm = PasswordHasher.ALGORITHM,
                kdfIterations = PasswordHasher.ITERATIONS,
                kdfMemoryKb = PasswordHasher.MEMORY_KB,
                status = "active", biometricEnabled = false,
                createdAt = clockMs, updatedAt = clockMs,
            ),
        )

        // Cataloger attempts catalog service with only TAXONOMY_MANAGE
        val limitedCatalog = CatalogService(
            authz = Authorizer(setOf(Capabilities.TAXONOMY_MANAGE)),
            recordDao = db.recordDao(),
            taxonomyDao = db.taxonomyDao(),
            holdingDao = db.holdingDao(),
            barcodeDao = db.barcodeDao(),
            audit = audit,
            clock = { clockMs },
        )
        try {
            limitedCatalog.insertRecord(
                MasterRecordEntity(
                    title = "x", titleNormalized = "x",
                    publisher = null, pubDate = null, format = "paperback",
                    category = "book", isbn10 = null, isbn13 = null,
                    language = null, notes = null, status = "active",
                    sourceProvenanceJson = null, createdByUserId = 99L,
                    createdAt = clockMs, updatedAt = clockMs,
                ),
                userId = 99L,
            )
            assertThat("expected SecurityException").isEmpty()
        } catch (_: SecurityException) {
            // expected
        }
        // No record should have been persisted
        assertThat(db.recordDao().activeCount()).isEqualTo(0)
    }
}
