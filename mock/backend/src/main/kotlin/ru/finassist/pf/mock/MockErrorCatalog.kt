package ru.finassist.pf.mock

import ru.finassist.pf.core.api.model.AnswerErrorCode
import ru.finassist.pf.core.common.error.AppError
import ru.finassist.pf.core.common.error.ErrorPresentation
import ru.finassist.pf.core.common.error.ErrorResponseInfo
import ru.finassist.pf.core.common.error.ErrorSeverity
import ru.finassist.pf.core.common.error.UserMessage
import ru.finassist.pf.core.common.time.ResponseTiming
import java.time.OffsetDateTime

/** The fake SERVER's presentation catalog. Clients consume the supplied text, including future codes. */
internal object MockErrorCatalog {
    val answerTexts = mapOf(
        AnswerErrorCode.MODEL_ERROR to "Не получилось ответить. Вопрос не потрачен",
        AnswerErrorCode.CANNOT_ANSWER to "Не получилось ответить — недостаточно данных, переформулируйте вопрос. Вопрос не потрачен",
        AnswerErrorCode.OFF_TOPIC to "Вопрос не по теме — помощник отвечает только на вопросы о ваших финансах. " +
            "С другими вопросами помогут универсальные ассистенты: Алиса AI, GigaChat, ChatGPT, Claude, DeepSeek",
    )

    fun presentation(code: String): ErrorPresentation {
        val text = when (code) {
            "CLIENT_VERSION_UNSUPPORTED" -> "Обновите приложение, чтобы продолжить"
            "MAINTENANCE" -> "Сервис временно недоступен. Попробуйте позже"
            "VALIDATION_ERROR" -> "Проверьте введённые данные"
            "INVALID_TOKEN", "TOKEN_EXPIRED", "TOKEN_REVOKED" -> "Войдите в аккаунт снова"
            "CONSENT_REQUIRED" -> "Для продолжения нужно ваше согласие"
            "CONSENT_OUTDATED" -> "Ознакомьтесь с обновлённым согласием"
            "LIMIT_EXCEEDED" -> "Вопросы на сегодня закончились"
            "RATE_LIMITED" -> "Слишком много запросов. Попробуйте позже"
            "NOT_FOUND" -> "Данные не найдены"
            "IDEMPOTENCY_CONFLICT" -> "Не удалось выполнить запрос. Обновите данные"
            "REQUEST_IN_PROGRESS" -> "Запрос ещё выполняется"
            "FILE_TOO_LARGE" -> "Размер файла превышает допустимый"
            "WRONG_FORMAT" -> "Загрузите выписку в формате OFX"
            "CSV_NOT_ACCEPTED" -> "Выписки CSV пока не поддерживаются"
            "WRONG_BANK" -> "Загрузите выписку поддерживаемого банка"
            "TOO_MANY_RESULTS" -> "Уточните условия поиска"
            "SELECTION_NOT_FOUND" -> "Данные изменились. Повторите поиск"
            "CATEGORY_NOT_ASSIGNABLE" -> "Эту категорию нельзя назначить операции"
            "RETRY_NOT_ALLOWED" -> "Этот ответ нельзя повторить"
            "CANCELLED" -> "Загрузка отменена"
            "PROCESSING_FAILED" -> "Не удалось обработать выписку. Попробуйте загрузить её снова"
            else -> answerTexts.entries.firstOrNull { it.key.name == code }?.value ?: "Не удалось выполнить запрос. Попробуйте позже"
        }
        val severity = if (code in setOf("OFF_TOPIC", "LIMIT_EXCEEDED", "RATE_LIMITED", "REQUEST_IN_PROGRESS", "CANCELLED")) ErrorSeverity.WARNING else ErrorSeverity.ERROR
        return ErrorPresentation(UserMessage.from(text), severity)
    }
}

internal fun MockBackend.errorInfo(
    code: String,
    status: Int,
    details: List<AppError.FieldError> = emptyList(),
    retryAt: OffsetDateTime? = null,
    resetsAt: OffsetDateTime? = null,
    until: OffsetDateTime? = null,
    requestRetryable: Boolean = false,
    retryAfterSeconds: Long? = null,
): ErrorResponseInfo = ErrorResponseInfo(
    code, status, details, retryAt, resetsAt, until, MockErrorCatalog.presentation(code), requestRetryable,
    ResponseTiming(now().toInstant(), elapsedClock?.nowMillis()), retryAfterSeconds = retryAfterSeconds,
)

internal fun MockBackend.apiError(
    code: String, httpStatus: Int, details: List<AppError.FieldError> = emptyList(),
    retryAt: OffsetDateTime? = null, resetsAt: OffsetDateTime? = null, message: String? = null,
): AppError.Api = AppError.Api(code, httpStatus, details, retryAt, resetsAt, message,
    response = errorInfo(code, httpStatus, details, retryAt, resetsAt))

internal fun MockBackend.unauthorizedError(code: String) = AppError.Unauthorized(code, errorInfo(code, 401))
internal fun MockBackend.rateLimitedError(retryAt: OffsetDateTime?, retryAfterSeconds: Long? = null, requestRetryable: Boolean = false) =
    AppError.RateLimited(retryAt, retryAfterSeconds, errorInfo("RATE_LIMITED", 429, retryAt = retryAt, requestRetryable = requestRetryable, retryAfterSeconds = retryAfterSeconds))
internal fun MockBackend.inProgressError(retryAfterSeconds: Int?) = AppError.InProgress(retryAfterSeconds,
    errorInfo("REQUEST_IN_PROGRESS", 409, retryAfterSeconds = retryAfterSeconds?.toLong()))
internal fun MockBackend.maintenanceError(until: OffsetDateTime?, retryAfterSeconds: Int?) = AppError.Maintenance(until, retryAfterSeconds,
    errorInfo("MAINTENANCE", 503, until = until, retryAfterSeconds = retryAfterSeconds?.toLong()))
internal fun MockBackend.updateRequiredError() = AppError.UpdateRequired(errorInfo("CLIENT_VERSION_UNSUPPORTED", 400))

/** Decode controls through the same tolerant wire model as production, including malformed canonical fields. */
internal fun MockBackend.injectedError(scenario: MockErrorScenario): AppError {
    if (scenario == MockErrorScenario.TERMINAL_FAILURE) return AppError.Server(500, response = errorInfo("INTERNAL_ERROR", 500, requestRetryable = false))
    if (scenario == MockErrorScenario.RETRY_FORBIDDEN) return rateLimitedError(now().plusSeconds(3), 3)
    val code = "FUTURE_OPERATION_FAILURE"
    val canonical = when (scenario) {
        MockErrorScenario.UNKNOWN_CODE -> "\"user_message\":\"Сервис получил новый тип ошибки. Попробуйте позже\",\"severity\":\"warning\""
        MockErrorScenario.UNKNOWN_SEVERITY -> "\"user_message\":\"Сервис получил новый тип ошибки. Попробуйте позже\",\"severity\":\"future_level\""
        MockErrorScenario.INVALID_MESSAGE -> "\"user_message\":null,\"severity\":\"warning\""
        MockErrorScenario.LEGACY_RESPONSE -> ""
        MockErrorScenario.RETRY_FORBIDDEN, MockErrorScenario.TERMINAL_FAILURE -> error("handled above")
    }
    val json = "{\"code\":\"$code\",\"message\":\"internal diagnostic must not be displayed\"" +
        (if (canonical.isEmpty()) "" else ",$canonical") + "}"
    val wire = ru.finassist.pf.core.api.ApiJson.decodeFromString(ru.finassist.pf.core.api.model.ApiError.serializer(), json)
    return AppError.Api(code, 422, response = errorInfo(code, 422).copy(
        presentation = wire.presentation, requestRetryable = wire.retryable,
    ))
}
