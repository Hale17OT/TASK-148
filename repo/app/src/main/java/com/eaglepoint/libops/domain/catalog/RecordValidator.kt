package com.eaglepoint.libops.domain.catalog

import com.eaglepoint.libops.domain.FieldError

/**
 * Master record validation (§9.7, §17).
 */
object RecordValidator {
    enum class Category { BOOK, JOURNAL, OTHER }

    data class Input(
        val title: String?,
        val publisher: String?,
        val pubDateEpochMillis: Long?,
        val format: String?,
        val category: Category,
        val isbn10: String?,
        val isbn13: String?,
        val nowEpochMillis: Long,
    )

    fun validate(input: Input): List<FieldError> {
        val errors = mutableListOf<FieldError>()

        if (input.title.isNullOrBlank()) {
            errors += FieldError("title", "required", "Title is required")
        }
        if (input.publisher != null && input.publisher.length > 255) {
            errors += FieldError("publisher", "too_long", "Publisher must be at most 255 characters")
        }
        input.pubDateEpochMillis?.let {
            if (it > input.nowEpochMillis) {
                errors += FieldError("pubDate", "future_not_allowed", "Publication date cannot be in the future")
            }
        }
        when (input.category) {
            Category.BOOK -> {
                if (input.format.isNullOrBlank()) {
                    errors += FieldError("format", "required", "Format is required for books")
                }
            }
            Category.JOURNAL -> {
                if (input.publisher.isNullOrBlank()) {
                    errors += FieldError("publisher", "required", "Publisher is required for journals")
                }
            }
            Category.OTHER -> { /* only title required */ }
        }

        if (input.isbn10 != null && !IsbnValidator.isValidIsbn10(IsbnValidator.normalize(input.isbn10)!!)) {
            errors += FieldError("isbn10", "invalid_checksum", "ISBN-10 checksum is invalid")
        }
        if (input.isbn13 != null && !IsbnValidator.isValidIsbn13(IsbnValidator.normalize(input.isbn13)!!)) {
            errors += FieldError("isbn13", "invalid_checksum", "ISBN-13 checksum is invalid")
        }
        if (!IsbnValidator.consistent(input.isbn10, input.isbn13)) {
            errors += FieldError("isbn13", "inconsistent", "ISBN-10 and ISBN-13 do not refer to the same work")
        }

        return errors
    }
}
