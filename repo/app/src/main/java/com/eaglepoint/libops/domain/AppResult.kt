package com.eaglepoint.libops.domain

/**
 * Standard result envelope for domain operations. See PRD §12.
 */
sealed interface AppResult<out T> {
    data class Success<T>(val data: T) : AppResult<T>
    data class ValidationError(val fieldErrors: List<FieldError>) : AppResult<Nothing>
    data class PermissionDenied(val permission: String) : AppResult<Nothing>
    data class Conflict(val entity: String, val reason: String) : AppResult<Nothing>
    data class NotFound(val entity: String) : AppResult<Nothing>
    data class Locked(val minutesRemaining: Int) : AppResult<Nothing>
    data class SystemError(val correlationId: String, val message: String) : AppResult<Nothing>
}

data class FieldError(val field: String, val code: String, val message: String)
