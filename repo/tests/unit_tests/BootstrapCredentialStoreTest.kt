package com.eaglepoint.libops.tests

import com.eaglepoint.libops.security.BootstrapCredentialStore
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Before
import org.junit.Test

/**
 * Tests the [BootstrapCredentialStore] contract using a pure-JVM fake that
 * mirrors the behavior of [EncryptedBootstrapCredentialStore]. This verifies
 * the store/peek/consume lifecycle, one-time semantics, and the flow-based
 * handoff that LibOpsApp + LoginActivity depend on.
 */
class BootstrapCredentialStoreTest {

    private lateinit var store: FakeBootstrapCredentialStore

    @Before
    fun setUp() {
        store = FakeBootstrapCredentialStore()
    }

    @Test
    fun store_and_peek_returns_value() {
        store.store("Secret!Pass123")
        assertThat(store.peek()).isEqualTo("Secret!Pass123")
    }

    @Test
    fun peek_returns_null_when_empty() {
        assertThat(store.peek()).isNull()
    }

    @Test
    fun consume_returns_value_and_removes() {
        store.store("OneTime!Pass99")
        val consumed = store.consume()
        assertThat(consumed).isEqualTo("OneTime!Pass99")
        assertThat(store.peek()).isNull()
        assertThat(store.consume()).isNull()
    }

    @Test
    fun consume_is_idempotent() {
        store.store("Pass!word123")
        store.consume()
        assertThat(store.consume()).isNull()
        assertThat(store.consume()).isNull()
    }

    @Test
    fun store_overwrites_previous() {
        store.store("First!Pass123")
        store.store("Second!Pass456")
        assertThat(store.peek()).isEqualTo("Second!Pass456")
    }

    // --- Flow-based handoff integration (mirrors LibOpsApp + LoginActivity) ---

    @Test
    fun flow_handoff_with_store_contract() {
        val flow = MutableStateFlow<String?>(null)

        // Simulate LibOpsApp.onCreate: seed → store → emit
        store.store("Bootstrap!Pass")
        flow.value = store.peek()
        assertThat(flow.value).isEqualTo("Bootstrap!Pass")

        // Simulate LoginActivity: observe → re-verify from store → show dialog
        val verified = store.peek()
        assertThat(verified).isEqualTo("Bootstrap!Pass")

        // Simulate acknowledgement: consume + clear flow
        store.consume()
        flow.value = null
        assertThat(flow.value).isNull()
        assertThat(store.peek()).isNull()
    }

    @Test
    fun flow_not_reappearing_after_acknowledgement() {
        val flow = MutableStateFlow<String?>(null)

        store.store("Cred!ential1")
        flow.value = store.peek()

        // Acknowledge
        store.consume()
        flow.value = null

        // Simulate activity recreation: re-check store
        val afterRecreation = store.peek()
        assertThat(afterRecreation).isNull()
        assertThat(flow.value).isNull()
    }

    @Test
    fun process_death_recovery_before_acknowledgement() {
        // First process: seed + store
        store.store("Survives!Death1")

        // Simulate process death: flow is gone, store persists
        val flow = MutableStateFlow<String?>(null)

        // Second process: recover from store
        flow.value = store.peek()
        assertThat(flow.value).isEqualTo("Survives!Death1")

        // Acknowledge in second process
        store.consume()
        flow.value = null
        assertThat(store.peek()).isNull()
    }

    @Test
    fun process_death_after_acknowledgement_stays_cleared() {
        store.store("Cleared!Pass1")
        store.consume()

        // Second process: nothing to recover
        val flow = MutableStateFlow<String?>(null)
        flow.value = store.peek()
        assertThat(flow.value).isNull()
    }
}

/**
 * Pure-JVM fake implementing [BootstrapCredentialStore] with the same
 * semantics as [EncryptedBootstrapCredentialStore] but backed by a
 * simple in-memory map instead of EncryptedSharedPreferences.
 */
class FakeBootstrapCredentialStore : BootstrapCredentialStore {
    private var value: String? = null

    override fun store(password: String) { value = password }
    override fun peek(): String? = value
    override fun consume(): String? {
        val pw = value
        value = null
        return pw
    }
}
