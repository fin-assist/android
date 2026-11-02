package ru.finassist.pf.core.common.time

import java.time.DateTimeException
import java.time.ZoneId
import ru.finassist.pf.core.common.error.AppError
import ru.finassist.pf.core.common.error.ErrorPresentation
import ru.finassist.pf.core.common.error.ErrorResponseInfo
import ru.finassist.pf.core.common.error.UserMessage

/** Resolves the response-owned IANA zone; never substitutes the profile, wire offset or an alias. */
object SourceTimeZone {
    const val UNSUPPORTED = "LOCAL_TIME_ZONE_UNSUPPORTED"
    const val MESSAGE = "Устройство не поддерживает часовой пояс этих данных"

    fun require(value: String?): ZoneId {
        if (value.isNullOrBlank() || value.length > 128 || !value.matches(Regex("[A-Za-z][A-Za-z0-9._+/-]*"))) {
            throw AppError.Server(500)
        }
        return try {
            ZoneId.of(value)
        } catch (_: DateTimeException) {
            throw AppError.Api(UNSUPPORTED, 400, response = ErrorResponseInfo(
                code = UNSUPPORTED, httpStatus = 400,
                presentation = ErrorPresentation(userMessage = UserMessage.from(MESSAGE)),
            ))
        }
    }
}
