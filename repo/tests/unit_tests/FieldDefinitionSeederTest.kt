package com.eaglepoint.libops.tests

import androidx.test.core.app.ApplicationProvider
import com.eaglepoint.libops.data.db.FieldDefinitionSeeder
import com.eaglepoint.libops.data.db.LibOpsDatabase
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Real-Room tests for [FieldDefinitionSeeder.ensureSeeded] covering idempotent
 * insertion, field-type correctness, and the system-vs-user field invariant
 * (§9.7).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class FieldDefinitionSeederTest {

    private lateinit var db: LibOpsDatabase
    private val clockMs = 1_700_000_000_000L

    @Before
    fun setUp() {
        db = LibOpsDatabase.inMemory(
            ApplicationProvider.getApplicationContext<android.content.Context>()
        )
    }

    @After
    fun tearDown() { db.close() }

    @Test
    fun seeding_empty_database_inserts_all_nine_built_in_fields(): Unit = runBlocking {
        assertThat(db.fieldDefinitionDao().allFields()).isEmpty()
        FieldDefinitionSeeder.ensureSeeded(db.fieldDefinitionDao(), clock = { clockMs })
        val fields = db.fieldDefinitionDao().allFields()
        assertThat(fields).hasSize(9)
    }

    @Test
    fun seeded_fields_include_title_publisher_and_isbn13(): Unit = runBlocking {
        FieldDefinitionSeeder.ensureSeeded(db.fieldDefinitionDao(), clock = { clockMs })
        val keys = db.fieldDefinitionDao().allFields().map { it.fieldKey }.toSet()
        assertThat(keys).containsAtLeast("title", "publisher", "isbn13")
    }

    @Test
    fun title_field_is_required_by_default(): Unit = runBlocking {
        FieldDefinitionSeeder.ensureSeeded(db.fieldDefinitionDao(), clock = { clockMs })
        val title = db.fieldDefinitionDao().allFields().first { it.fieldKey == "title" }
        assertThat(title.required).isTrue()
        assertThat(title.system).isTrue()
        assertThat(title.entityColumn).isEqualTo("title")
    }

    @Test
    fun category_field_is_select_type_and_required(): Unit = runBlocking {
        FieldDefinitionSeeder.ensureSeeded(db.fieldDefinitionDao(), clock = { clockMs })
        val cat = db.fieldDefinitionDao().allFields().first { it.fieldKey == "category" }
        assertThat(cat.fieldType).isEqualTo("select")
        assertThat(cat.required).isTrue()
    }

    @Test
    fun pub_date_field_is_date_type(): Unit = runBlocking {
        FieldDefinitionSeeder.ensureSeeded(db.fieldDefinitionDao(), clock = { clockMs })
        val date = db.fieldDefinitionDao().allFields().first { it.fieldKey == "pub_date" }
        assertThat(date.fieldType).isEqualTo("date")
    }

    @Test
    fun all_seeded_fields_are_marked_as_system(): Unit = runBlocking {
        FieldDefinitionSeeder.ensureSeeded(db.fieldDefinitionDao(), clock = { clockMs })
        val fields = db.fieldDefinitionDao().allFields()
        assertThat(fields.all { it.system }).isTrue()
    }

    @Test
    fun seeding_twice_is_idempotent_no_duplicates_created(): Unit = runBlocking {
        FieldDefinitionSeeder.ensureSeeded(db.fieldDefinitionDao(), clock = { clockMs })
        FieldDefinitionSeeder.ensureSeeded(db.fieldDefinitionDao(), clock = { clockMs })
        FieldDefinitionSeeder.ensureSeeded(db.fieldDefinitionDao(), clock = { clockMs })
        assertThat(db.fieldDefinitionDao().allFields()).hasSize(9)
    }

    @Test
    fun display_order_is_monotonic_across_seeded_fields(): Unit = runBlocking {
        FieldDefinitionSeeder.ensureSeeded(db.fieldDefinitionDao(), clock = { clockMs })
        val orders = db.fieldDefinitionDao().allFields().map { it.displayOrder }
        assertThat(orders).containsExactlyElementsIn(orders.sorted()).inOrder()
    }

    @Test
    fun seeding_preserves_timestamps_from_injected_clock(): Unit = runBlocking {
        FieldDefinitionSeeder.ensureSeeded(db.fieldDefinitionDao(), clock = { clockMs })
        val fields = db.fieldDefinitionDao().allFields()
        assertThat(fields.all { it.createdAt == clockMs }).isTrue()
        assertThat(fields.all { it.updatedAt == clockMs }).isTrue()
    }
}
