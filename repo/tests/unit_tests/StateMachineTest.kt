package com.eaglepoint.libops.tests

import com.eaglepoint.libops.domain.statemachine.AlertStateMachine
import com.eaglepoint.libops.domain.statemachine.BarcodeStateMachine
import com.eaglepoint.libops.domain.statemachine.CollectionSourceStateMachine
import com.eaglepoint.libops.domain.statemachine.DuplicateStateMachine
import com.eaglepoint.libops.domain.statemachine.ImportBatchStateMachine
import com.eaglepoint.libops.domain.statemachine.JobStateMachine
import com.eaglepoint.libops.domain.statemachine.SessionStateMachine
import com.eaglepoint.libops.domain.statemachine.UserAccountStateMachine
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class StateMachineTest {

    @Test
    fun user_account_allows_pending_to_active() {
        assertThat(UserAccountStateMachine.canTransition("pending_activation", "active")).isTrue()
    }

    @Test
    fun user_account_blocks_active_to_pending() {
        assertThat(UserAccountStateMachine.canTransition("active", "pending_activation")).isFalse()
    }

    @Test
    fun user_account_blocks_disabled_to_locked() {
        assertThat(UserAccountStateMachine.canTransition("disabled", "locked")).isFalse()
    }

    @Test
    fun session_allows_created_to_authenticated() {
        assertThat(SessionStateMachine.canTransition("created", "authenticated")).isTrue()
    }

    @Test
    fun session_expired_is_terminal() {
        assertThat(SessionStateMachine.canTransition("expired", "authenticated")).isFalse()
    }

    @Test
    fun collection_source_archived_is_terminal() {
        assertThat(CollectionSourceStateMachine.canTransition("archived", "active")).isFalse()
    }

    @Test
    fun job_succeeded_is_terminal() {
        assertThat(JobStateMachine.canTransition("succeeded", "queued")).isFalse()
    }

    @Test
    fun job_can_pause_from_running() {
        assertThat(JobStateMachine.canTransition("running", "paused_low_battery")).isTrue()
    }

    @Test
    fun job_paused_resumes_to_queued() {
        assertThat(JobStateMachine.canTransition("paused_low_battery", "queued")).isTrue()
    }

    @Test
    fun job_retry_waiting_returns_to_queued_or_fails_terminal() {
        assertThat(JobStateMachine.canTransition("retry_waiting", "queued")).isTrue()
        assertThat(JobStateMachine.canTransition("retry_waiting", "failed")).isTrue()
        assertThat(JobStateMachine.canTransition("terminal_failed", "queued")).isFalse()
    }

    @Test
    fun import_batch_cannot_skip_validation() {
        assertThat(ImportBatchStateMachine.canTransition("received", "staged")).isFalse()
        assertThat(ImportBatchStateMachine.canTransition("validating", "staged")).isTrue()
    }

    @Test
    fun duplicate_can_be_reversed_from_merged() {
        assertThat(DuplicateStateMachine.canTransition("merged", "reversed")).isTrue()
    }

    @Test
    fun alert_cannot_jump_from_open_to_resolved() {
        assertThat(AlertStateMachine.canTransition("open", "resolved")).isFalse()
        assertThat(AlertStateMachine.canTransition("open", "acknowledged")).isTrue()
        assertThat(AlertStateMachine.canTransition("acknowledged", "resolved")).isTrue()
    }

    @Test
    fun barcode_retired_then_reserved_only() {
        assertThat(BarcodeStateMachine.canTransition("retired", "assigned")).isFalse()
        assertThat(BarcodeStateMachine.canTransition("retired", "reserved_hold")).isTrue()
    }
}
