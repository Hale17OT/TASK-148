package com.eaglepoint.libops.tests.support

import com.eaglepoint.libops.auth.SessionStore
import com.eaglepoint.libops.domain.auth.Roles

/**
 * Shared session builders for Robolectric Activity lifecycle tests.
 * Centralizes the ActiveSession construction so individual tests only
 * specify what differs from the defaults.
 */
object TestSessions {

    fun session(
        sessionId: Long = 1L,
        userId: Long = 1L,
        username: String = "admin",
        roleName: String = Roles.ADMIN,
        capabilities: Set<String> = Roles.DEFAULT_MAPPING[Roles.ADMIN]!!,
        biometricEnabled: Boolean = false,
        nowMillis: Long = System.currentTimeMillis(),
    ) = SessionStore.ActiveSession(
        sessionId = sessionId,
        userId = userId,
        username = username,
        activeRoleName = roleName,
        capabilities = capabilities,
        authenticatedAt = nowMillis,
        lastActiveMillis = nowMillis,
        biometricEnabled = biometricEnabled,
    )

    fun adminSession() = session(roleName = Roles.ADMIN, capabilities = Roles.DEFAULT_MAPPING[Roles.ADMIN]!!)

    fun catalogerSession() = session(
        userId = 2L, username = "cat_reviewer",
        roleName = Roles.CATALOGER,
        capabilities = Roles.DEFAULT_MAPPING[Roles.CATALOGER]!!,
    )

    fun collectionManagerSession() = session(
        userId = 3L, username = "cm_reviewer",
        roleName = Roles.COLLECTION_MANAGER,
        capabilities = Roles.DEFAULT_MAPPING[Roles.COLLECTION_MANAGER]!!,
    )

    fun auditorSession() = session(
        userId = 4L, username = "aud_reviewer",
        roleName = Roles.AUDITOR,
        capabilities = Roles.DEFAULT_MAPPING[Roles.AUDITOR]!!,
    )
}
