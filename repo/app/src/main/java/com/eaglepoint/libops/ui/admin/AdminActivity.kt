package com.eaglepoint.libops.ui.admin

import android.content.Intent
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.eaglepoint.libops.LibOpsApp
import com.eaglepoint.libops.data.db.entity.UserEntity
import com.eaglepoint.libops.data.db.entity.UserRoleEntity
import com.eaglepoint.libops.databinding.ActivityFeatureBinding
import com.eaglepoint.libops.domain.auth.Capabilities
import com.eaglepoint.libops.observability.QueryTimer
import com.eaglepoint.libops.domain.auth.PasswordHasher
import com.eaglepoint.libops.domain.auth.PasswordPolicy
import com.eaglepoint.libops.domain.auth.Roles
import com.eaglepoint.libops.domain.auth.UsernamePolicy
import com.eaglepoint.libops.ui.AuthorizationGate
import com.eaglepoint.libops.ui.FeatureScreenHelper
import com.eaglepoint.libops.ui.TwoLineRow
import com.eaglepoint.libops.ui.chipToneFor
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AdminActivity : FragmentActivity() {

    private lateinit var binding: ActivityFeatureBinding
    private lateinit var helper: FeatureScreenHelper
    private var adminUserId: Long = -1L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val session = AuthorizationGate.requireAccess(this, Capabilities.USERS_MANAGE) ?: return
        binding = ActivityFeatureBinding.inflate(layoutInflater)
        setContentView(binding.root)
        helper = FeatureScreenHelper(this, binding)
        adminUserId = session.userId

        helper.setup(
            eyebrow = "Governance",
            title = "Admin",
            subtitle = "Users, roles, runtime settings. Tap a user to manage.",
            fabLabel = "Actions",
            onRowClick = { row -> showUserActions(row.id.removePrefix("u-").toLongOrNull() ?: return@setup) },
            onFabClick = { showAdminMenu() },
        )
        lifecycleScope.launch { refresh() }
    }

    override fun onResume() {
        super.onResume()
        if (::helper.isInitialized) lifecycleScope.launch { refresh() }
    }

    private suspend fun refresh() {
        val app = application as LibOpsApp
        val queryTimer = QueryTimer(app.observabilityPipeline)
        val users = withContext(Dispatchers.IO) {
            queryTimer.timed("query", "userDao.listAll") { app.db.userDao().listAll() }
        }
        val rows = users.map { u ->
            val roles = withContext(Dispatchers.IO) {
                queryTimer.timed("query", "permissionDao.rolesForUser") { app.db.permissionDao().rolesForUser(u.id) }
            }
            val roleLabel = roles.joinToString(", ") { it.name }
            TwoLineRow(
                id = "u-${u.id}",
                primary = "${u.username} (${u.displayName})",
                secondary = "role: $roleLabel \u2022 attempts: ${u.failedAttempts} \u2022 biometric: ${if (u.biometricEnabled) "on" else "off"}",
                chipLabel = u.status,
                chipTone = chipToneFor(u.status),
            )
        }
        helper.submit(
            rows,
            emptyTitle = "No users",
            emptyBody = "Seed should have created the admin account on first launch.",
        )
    }

    private fun showAdminMenu() {
        val items = arrayOf(
            "Create new user",
            "Runtime settings",
            "Rotate signing key",
            "Manage trusted signing keys",
            "Add external trusted key",
        )
        AlertDialog.Builder(this)
            .setTitle("Admin actions")
            .setItems(items) { _, i ->
                when (i) {
                    0 -> showCreateUserDialog()
                    1 -> startActivity(Intent(this, RuntimeSettingsActivity::class.java))
                    2 -> rotateSigningKey()
                    3 -> showTrustedKeys()
                    4 -> showAddTrustedKeyDialog()
                }
            }.show()
    }

    private fun showCreateUserDialog() {
        val dp16 = (16 * resources.displayMetrics.density).toInt()
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp16, dp16, dp16, dp16 / 2)
        }
        val usernameInput = EditText(this).apply { hint = "Username" }
        val displayInput = EditText(this).apply { hint = "Display name" }
        val passwordInput = EditText(this).apply {
            hint = "Initial password"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val roleNames = listOf(Roles.ADMIN, Roles.COLLECTION_MANAGER, Roles.CATALOGER, Roles.AUDITOR)
        val roleSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@AdminActivity, android.R.layout.simple_spinner_dropdown_item, roleNames)
        }
        layout.addView(TextView(this).apply { text = "Username"; textSize = 12f })
        layout.addView(usernameInput)
        layout.addView(TextView(this).apply { text = "Display name"; textSize = 12f })
        layout.addView(displayInput)
        layout.addView(TextView(this).apply { text = "Initial password"; textSize = 12f })
        layout.addView(passwordInput)
        layout.addView(TextView(this).apply { text = "Role"; textSize = 12f })
        layout.addView(roleSpinner)

        AlertDialog.Builder(this)
            .setTitle("Create user")
            .setView(layout)
            .setPositiveButton("Create") { _, _ ->
                val username = usernameInput.text.toString().trim()
                val display = displayInput.text.toString().trim().ifEmpty { username }
                val password = passwordInput.text.toString()
                val roleName = roleSpinner.selectedItem as String
                createUser(username, display, password, roleName)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun createUser(username: String, displayName: String, password: String, roleName: String) {
        if (AuthorizationGate.revalidateSession(this, Capabilities.USERS_MANAGE) == null) return
        val usernameErrors = UsernamePolicy.validate(username)
        val passwordErrors = PasswordPolicy.validate(password)
        val allErrors = usernameErrors + passwordErrors
        if (allErrors.isNotEmpty()) {
            Snackbar.make(binding.root, allErrors.joinToString("\n") { it.message }, Snackbar.LENGTH_LONG).show()
            return
        }
        val app = application as LibOpsApp
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val now = System.currentTimeMillis()
                    val salt = PasswordHasher.generateSalt()
                    val hash = PasswordHasher.hash(password.toCharArray(), salt)
                    val entity = UserEntity(
                        username = username.lowercase(),
                        displayName = displayName,
                        passwordHash = PasswordHasher.encodeBase64(hash),
                        passwordSalt = PasswordHasher.encodeBase64(salt),
                        kdfAlgorithm = PasswordHasher.ALGORITHM,
                        kdfIterations = PasswordHasher.ITERATIONS,
                        kdfMemoryKb = PasswordHasher.MEMORY_KB,
                        status = "password_reset_required",
                        biometricEnabled = false,
                        createdAt = now,
                        updatedAt = now,
                    )
                    val userId = app.db.userDao().insert(entity)
                    val queryTimer = QueryTimer(app.observabilityPipeline)
                    val role = queryTimer.timed("query", "permissionDao.roleByName") { app.db.permissionDao().roleByName(roleName) }!!
                    app.db.permissionDao().assignRole(
                        UserRoleEntity(
                            userId = userId, roleId = role.id,
                            active = true, assignedAt = now,
                            assignedByUserId = adminUserId,
                        ),
                    )
                    app.auditLogger.record(
                        action = "user.created",
                        targetType = "user",
                        targetId = userId.toString(),
                        userId = adminUserId,
                        reason = "role=$roleName",
                    )
                }
                Snackbar.make(binding.root, "User '$username' created with role $roleName", Snackbar.LENGTH_LONG).show()
                refresh()
            } catch (e: Exception) {
                Snackbar.make(binding.root, "Error: ${e.message}", Snackbar.LENGTH_LONG).show()
            }
        }
    }

    private fun showUserActions(userId: Long) {
        val app = application as LibOpsApp
        lifecycleScope.launch {
            val queryTimer = QueryTimer(app.observabilityPipeline)
            val user = withContext(Dispatchers.IO) { queryTimer.timed("query", "userDao.findById") { app.db.userDao().findById(userId) } } ?: return@launch
            val items = mutableListOf("Change role")
            if (user.status == "active") items.add("Disable account")
            if (user.status == "disabled") items.add("Re-enable account")
            if (user.status == "locked") items.add("Unlock account")
            items.add("Reset password")

            AlertDialog.Builder(this@AdminActivity)
                .setTitle(user.username)
                .setItems(items.toTypedArray()) { _, i ->
                    when (items[i]) {
                        "Change role" -> showChangeRoleDialog(user)
                        "Disable account" -> setUserStatus(user, "disabled")
                        "Re-enable account" -> setUserStatus(user, "active")
                        "Unlock account" -> unlockUser(user)
                        "Reset password" -> showResetPasswordDialog(user)
                    }
                }.show()
        }
    }

    private fun showChangeRoleDialog(user: UserEntity) {
        val roleNames = listOf(Roles.ADMIN, Roles.COLLECTION_MANAGER, Roles.CATALOGER, Roles.AUDITOR)
        AlertDialog.Builder(this)
            .setTitle("Assign role to ${user.username}")
            .setItems(roleNames.toTypedArray()) { _, i ->
                assignRole(user, roleNames[i])
            }.show()
    }

    private fun assignRole(user: UserEntity, roleName: String) {
        if (AuthorizationGate.revalidateSession(this, Capabilities.ROLES_MANAGE) == null) return
        val app = application as LibOpsApp
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                val now = System.currentTimeMillis()
                val queryTimer = QueryTimer(app.observabilityPipeline)
                val role = queryTimer.timed("query", "permissionDao.roleByName") { app.db.permissionDao().roleByName(roleName) }!!
                app.db.permissionDao().assignRole(
                    UserRoleEntity(
                        userId = user.id, roleId = role.id,
                        active = true, assignedAt = now,
                        assignedByUserId = adminUserId,
                    ),
                )
                app.auditLogger.record(
                    action = "user.role_changed",
                    targetType = "user",
                    targetId = user.id.toString(),
                    userId = adminUserId,
                    reason = "new_role=$roleName",
                )
            }
            Snackbar.make(binding.root, "${user.username} assigned role $roleName", Snackbar.LENGTH_SHORT).show()
            refresh()
        }
    }

    private fun setUserStatus(user: UserEntity, status: String) {
        if (AuthorizationGate.revalidateSession(this, Capabilities.USERS_MANAGE) == null) return
        val app = application as LibOpsApp
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                val now = System.currentTimeMillis()
                app.db.userDao().update(user.copy(status = status, updatedAt = now))
                app.auditLogger.record(
                    action = "user.status_changed",
                    targetType = "user",
                    targetId = user.id.toString(),
                    userId = adminUserId,
                    reason = "new_status=$status",
                )
            }
            Snackbar.make(binding.root, "${user.username} set to $status", Snackbar.LENGTH_SHORT).show()
            refresh()
        }
    }

    private fun unlockUser(user: UserEntity) {
        if (AuthorizationGate.revalidateSession(this, Capabilities.USERS_MANAGE) == null) return
        val app = application as LibOpsApp
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                val now = System.currentTimeMillis()
                app.db.userDao().updateLockout(user.id, 0, null, "active", now)
                app.auditLogger.record(
                    action = "user.unlocked",
                    targetType = "user",
                    targetId = user.id.toString(),
                    userId = adminUserId,
                    reason = "admin_unlock",
                )
            }
            Snackbar.make(binding.root, "${user.username} unlocked", Snackbar.LENGTH_SHORT).show()
            refresh()
        }
    }

    private fun showResetPasswordDialog(user: UserEntity) {
        val passwordInput = EditText(this).apply {
            hint = "New password"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        AlertDialog.Builder(this)
            .setTitle("Reset password for ${user.username}")
            .setView(passwordInput)
            .setPositiveButton("Reset") { _, _ ->
                val newPassword = passwordInput.text.toString()
                val errors = PasswordPolicy.validate(newPassword)
                if (errors.isNotEmpty()) {
                    Snackbar.make(binding.root, errors.joinToString { it.message }, Snackbar.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                resetPassword(user, newPassword)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun resetPassword(user: UserEntity, newPassword: String) {
        if (AuthorizationGate.revalidateSession(this, Capabilities.USERS_MANAGE) == null) return
        val app = application as LibOpsApp
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                val now = System.currentTimeMillis()
                val salt = PasswordHasher.generateSalt()
                val hash = PasswordHasher.hash(newPassword.toCharArray(), salt)
                app.db.userDao().updatePassword(
                    id = user.id,
                    hash = PasswordHasher.encodeBase64(hash),
                    salt = PasswordHasher.encodeBase64(salt),
                    alg = PasswordHasher.ALGORITHM,
                    iters = PasswordHasher.ITERATIONS,
                    mem = PasswordHasher.MEMORY_KB,
                    now = now,
                )
                app.db.userDao().update(user.copy(status = "password_reset_required", updatedAt = now))
                app.auditLogger.record(
                    action = "user.password_reset",
                    targetType = "user",
                    targetId = user.id.toString(),
                    userId = adminUserId,
                    reason = "admin_reset",
                    severity = com.eaglepoint.libops.audit.AuditLogger.Severity.WARN,
                )
            }
            Snackbar.make(binding.root, "Password reset for ${user.username}", Snackbar.LENGTH_SHORT).show()
            refresh()
        }
    }

    private fun rotateSigningKey() {
        if (AuthorizationGate.revalidateSession(this, Capabilities.PERMISSIONS_MANAGE) == null) return
        AlertDialog.Builder(this)
            .setTitle("Rotate signing key")
            .setMessage("This generates a new signing keypair. Existing exports signed with the old key will still verify against the trusted key registry. Proceed?")
            .setPositiveButton("Rotate") { _, _ ->
                val app = application as LibOpsApp
                lifecycleScope.launch {
                    val newVersion = app.signingKeyStore.rotateSigningKey()
                    withContext(Dispatchers.IO) {
                        app.auditLogger.record(
                            action = "signing_key.rotated",
                            targetType = "signing_key",
                            targetId = "device_v$newVersion",
                            userId = adminUserId,
                            reason = "admin_initiated",
                            severity = com.eaglepoint.libops.audit.AuditLogger.Severity.CRITICAL,
                        )
                    }
                    Snackbar.make(binding.root, "Signing key rotated to v$newVersion", Snackbar.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showTrustedKeys() {
        val app = application as LibOpsApp
        val keys = app.signingKeyStore.trustedPublicKeys()
        val items = mutableListOf("Add external trusted key")
        val keyIds = mutableListOf<String?>(null) // null sentinel for the "add" row
        for ((id, key) in keys) {
            val fingerprint = java.security.MessageDigest.getInstance("SHA-256")
                .digest(key.encoded)
                .take(8)
                .joinToString("") { "%02x".format(it) }
            items.add("$id  ($fingerprint…)")
            keyIds.add(id)
        }
        AlertDialog.Builder(this)
            .setTitle("Trusted signing keys (${keys.size})")
            .setItems(items.toTypedArray()) { _, i ->
                val keyId = keyIds[i]
                if (keyId == null) {
                    showAddTrustedKeyDialog()
                } else {
                    showTrustedKeyActions(keyId)
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showAddTrustedKeyDialog() {
        val dp16 = (16 * resources.displayMetrics.density).toInt()
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp16, dp16, dp16, dp16 / 2)
        }
        val idInput = EditText(this).apply { hint = "Key ID (e.g. device_branch_01)" }
        val keyInput = EditText(this).apply {
            hint = "Base64-encoded public key (X.509/DER)"
            minLines = 3
        }
        layout.addView(TextView(this).apply { text = "Key identifier"; textSize = 12f })
        layout.addView(idInput)
        layout.addView(TextView(this).apply { text = "Public key (Base64)"; textSize = 12f })
        layout.addView(keyInput)

        AlertDialog.Builder(this)
            .setTitle("Add external trusted key")
            .setView(layout)
            .setPositiveButton("Add") { _, _ ->
                addTrustedKey(
                    idInput.text.toString().trim(),
                    keyInput.text.toString().trim(),
                )
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun addTrustedKey(keyId: String, base64PublicKey: String) {
        if (AuthorizationGate.revalidateSession(this, Capabilities.PERMISSIONS_MANAGE) == null) return
        if (keyId.isEmpty() || base64PublicKey.isEmpty()) {
            Snackbar.make(binding.root, "Key ID and public key are required", Snackbar.LENGTH_SHORT).show()
            return
        }
        val app = application as LibOpsApp
        lifecycleScope.launch {
            try {
                val decoded = android.util.Base64.decode(base64PublicKey, android.util.Base64.DEFAULT)
                val publicKey = java.security.KeyFactory.getInstance("RSA")
                    .generatePublic(java.security.spec.X509EncodedKeySpec(decoded))
                app.signingKeyStore.addTrustedPublicKey(keyId, publicKey)
                withContext(Dispatchers.IO) {
                    app.auditLogger.record(
                        action = "trusted_key.added",
                        targetType = "signing_key",
                        targetId = keyId,
                        userId = adminUserId,
                        reason = "admin_add_external",
                        severity = com.eaglepoint.libops.audit.AuditLogger.Severity.CRITICAL,
                    )
                }
                Snackbar.make(binding.root, "Trusted key '$keyId' added", Snackbar.LENGTH_LONG).show()
            } catch (e: Exception) {
                Snackbar.make(binding.root, "Invalid key: ${e.message}", Snackbar.LENGTH_LONG).show()
            }
        }
    }

    private fun showTrustedKeyActions(keyId: String) {
        if (keyId.startsWith("device_v")) {
            AlertDialog.Builder(this)
                .setTitle(keyId)
                .setMessage("This is the local device signing key. It cannot be removed. Use \"Rotate signing key\" to generate a new version.")
                .setPositiveButton("OK", null)
                .show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(keyId)
            .setItems(arrayOf("Remove trusted key")) { _, _ ->
                removeTrustedKey(keyId)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun removeTrustedKey(keyId: String) {
        if (AuthorizationGate.revalidateSession(this, Capabilities.PERMISSIONS_MANAGE) == null) return
        AlertDialog.Builder(this)
            .setTitle("Remove '$keyId'?")
            .setMessage("Bundles signed by this key will no longer verify on import. This cannot be undone.")
            .setPositiveButton("Remove") { _, _ ->
                val app = application as LibOpsApp
                lifecycleScope.launch {
                    app.signingKeyStore.removeTrustedPublicKey(keyId)
                    withContext(Dispatchers.IO) {
                        app.auditLogger.record(
                            action = "trusted_key.removed",
                            targetType = "signing_key",
                            targetId = keyId,
                            userId = adminUserId,
                            reason = "admin_remove_external",
                            severity = com.eaglepoint.libops.audit.AuditLogger.Severity.CRITICAL,
                        )
                    }
                    Snackbar.make(binding.root, "Trusted key '$keyId' removed", Snackbar.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
