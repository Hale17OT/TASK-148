package com.eaglepoint.libops.domain.catalog

/**
 * ISBN validation (§9.8).
 *
 * - Both ISBN-10 and ISBN-13 supported
 * - Canonical storage is digits only (with X for ISBN-10 check digit)
 * - Checksum must be valid before save
 * - ISBN-10 and ISBN-13 must refer to the same work when both provided
 */
object IsbnValidator {

    fun normalize(raw: String?): String? {
        if (raw == null) return null
        val stripped = raw.trim().uppercase().replace(Regex("[\\s-]"), "")
        return stripped.ifEmpty { null }
    }

    fun isValid(raw: String?): Boolean {
        val n = normalize(raw) ?: return false
        return when (n.length) {
            10 -> isValidIsbn10(n)
            13 -> isValidIsbn13(n)
            else -> false
        }
    }

    fun isValidIsbn10(isbn10: String): Boolean {
        if (isbn10.length != 10) return false
        var sum = 0
        for (i in 0 until 9) {
            val c = isbn10[i]
            if (!c.isDigit()) return false
            sum += (c - '0') * (10 - i)
        }
        val last = isbn10[9]
        val checkValue = when {
            last == 'X' -> 10
            last.isDigit() -> last - '0'
            else -> return false
        }
        sum += checkValue
        return sum % 11 == 0
    }

    fun isValidIsbn13(isbn13: String): Boolean {
        if (isbn13.length != 13) return false
        var sum = 0
        for (i in 0 until 12) {
            val c = isbn13[i]
            if (!c.isDigit()) return false
            val digit = c - '0'
            sum += if (i % 2 == 0) digit else digit * 3
        }
        val last = isbn13[12]
        if (!last.isDigit()) return false
        val expected = (10 - (sum % 10)) % 10
        return expected == last - '0'
    }

    /** Convert a valid ISBN-10 to ISBN-13 (prefix 978). */
    fun isbn10To13(isbn10: String): String? {
        if (!isValidIsbn10(isbn10)) return null
        val body = "978" + isbn10.substring(0, 9)
        var sum = 0
        for (i in body.indices) {
            val digit = body[i] - '0'
            sum += if (i % 2 == 0) digit else digit * 3
        }
        val check = (10 - (sum % 10)) % 10
        return body + check
    }

    /** Verify that both ISBN forms refer to the same work. */
    fun consistent(isbn10: String?, isbn13: String?): Boolean {
        val n10 = normalize(isbn10) ?: return true
        val n13 = normalize(isbn13) ?: return true
        if (!isValidIsbn10(n10) || !isValidIsbn13(n13)) return false
        return isbn10To13(n10) == n13
    }
}
