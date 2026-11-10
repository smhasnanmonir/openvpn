package com.smhasnan.openvpn.config

object ProfileNameValidator {
    const val MAX_LENGTH = 60

    fun validate(raw: String): Result<String> {
        val name = raw.trim()
        return when {
            name.isEmpty() -> Result.failure(IllegalArgumentException("Enter a profile name."))
            name.length > MAX_LENGTH -> Result.failure(
                IllegalArgumentException("Use at most $MAX_LENGTH characters.")
            )
            name.any { it.isISOControl() } -> Result.failure(
                IllegalArgumentException("The name contains unsupported characters.")
            )
            else -> Result.success(name)
        }
    }
}
