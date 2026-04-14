package com.eaglepoint.libops.domain.auth

import com.eaglepoint.libops.domain.FieldError

/**
 * Password complexity policy. See PRD §9.1 and §17.
 *
 * - Minimum length 12
 * - At least one uppercase, lowercase, digit, and symbol
 *
 * This is a pure function, fully unit-testable.
 */
object PasswordPolicy {
    const val MIN_LENGTH = 12

    fun validate(password: String): List<FieldError> {
        val errors = mutableListOf<FieldError>()
        if (password.length < MIN_LENGTH) {
            errors += FieldError("password", "too_short", "Password must be at least $MIN_LENGTH characters")
        }
        if (password.none { it.isUpperCase() }) {
            errors += FieldError("password", "missing_upper", "Password must contain an uppercase letter")
        }
        if (password.none { it.isLowerCase() }) {
            errors += FieldError("password", "missing_lower", "Password must contain a lowercase letter")
        }
        if (password.none { it.isDigit() }) {
            errors += FieldError("password", "missing_digit", "Password must contain a digit")
        }
        if (password.all { it.isLetterOrDigit() }) {
            errors += FieldError("password", "missing_symbol", "Password must contain a symbol")
        }
        return errors
    }

    fun isValid(password: String): Boolean = validate(password).isEmpty()
}

/**
 * Username validation per PRD §17.
 */
object UsernamePolicy {
    private val USERNAME_REGEX = Regex("^[a-zA-Z0-9._-]+$")

    fun validate(username: String): List<FieldError> {
        val errors = mutableListOf<FieldError>()
        if (username.length !in 3..64) {
            errors += FieldError("username", "length", "Username must be 3-64 characters")
        }
        if (!USERNAME_REGEX.matches(username)) {
            errors += FieldError("username", "format", "Username may contain letters, digits, and . _ -")
        }
        return errors
    }
}
