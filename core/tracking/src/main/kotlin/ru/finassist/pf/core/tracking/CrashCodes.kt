package ru.finassist.pf.core.tracking

import ru.finassist.pf.core.common.error.AppError

/**
 * Machine-readable reason of a handled error for [NonFatal.code]: the API error code in lower case
 * (`wrong_bank`), `offline`, `server_503`, … Never the error message — it may quote user data.
 */
fun Throwable.crashCode(): Slug = when (this) {
    is AppError.Offline -> Slug.of("offline")
    is AppError.Unauthorized -> Slug.orNull(code.lowercase()) ?: Slug.of("unauthorized")
    is AppError.RateLimited -> Slug.of("rate_limited")
    is AppError.InProgress -> Slug.of("request_in_progress")
    is AppError.Api -> Slug.orNull(code.lowercase()) ?: Slug.of("api_$httpStatus")
    is AppError.Server -> Slug.of("server_$httpStatus")
    is AppError.Unknown -> Slug.of("unknown")
    else -> Slug.of("unexpected")
}
