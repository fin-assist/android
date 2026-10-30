package ru.finassist.pf.e2e

/** Closed, bounded client faults. No arbitrary URL, response body, account or token can be supplied. */
object StageFaultPlan {
    val operations = setOf("profile_update", "operations_list", "assistant_ask", "phone_start", "off")
    val scenarios = setOf("unknown_code", "unknown_severity", "invalid_message", "legacy_response", "retry_forbidden", "terminal_failure")

    fun validate(name: String, value: String) {
        val valid = when (name) {
            "offline", "unknown_codes" -> value.toBooleanStrictOrNull() != null
            "network_delay_ms", "call_delay_ms" -> value.toLongOrNull() in 0L..60_000L
            "stream_drops", "stream_rate_limits" -> value.toIntOrNull() in 0..100
            "stream_retry_s", "maintenance_retry_after" -> value.toIntOrNull() in 0..120
            "error_operation" -> value in operations
            "error_scenario" -> value in scenarios
            "maintenance" -> value in setOf("off", "unknown") || value.toIntOrNull() in 0..60
            "min_version" -> value.isEmpty() || Regex("[0-9]{1,5}\\.[0-9]{1,5}\\.[0-9]{1,5}").matches(value)
            "failing_blocks" -> value in setOf("", "expense_categories")
            else -> false
        }
        require(valid) { "Unsupported or invalid stage control: $name" }
    }

    fun operation(method: String, path: String): String? = when {
        method == "PATCH" && path == "/v1/profile" -> "profile_update"
        method == "GET" && path == "/v1/operations" -> "operations_list"
        method == "POST" && path == "/v1/assistant/messages" -> "assistant_ask"
        method == "POST" && path == "/v1/auth/phone" -> "phone_start"
        else -> null
    }

    fun error(scenario: String): Pair<Int, String> {
        val fields = when (scenario) {
            "unknown_code" -> "\"user_message\":\"Сервис получил новый тип ошибки. Попробуйте позже\",\"severity\":\"warning\""
            "unknown_severity" -> "\"user_message\":\"Сервис получил новый тип ошибки. Попробуйте позже\",\"severity\":\"future_level\""
            "invalid_message" -> "\"user_message\":null,\"severity\":\"warning\""
            "legacy_response" -> ""
            "retry_forbidden" -> return 429 to "{\"error\":{\"code\":\"RATE_LIMITED\",\"user_message\":\"Слишком много запросов. Попробуйте позже\",\"severity\":\"warning\",\"retryable\":false}}"
            "terminal_failure" -> return 500 to "{\"error\":{\"code\":\"INTERNAL_ERROR\",\"user_message\":\"Не удалось выполнить запрос. Попробуйте позже\",\"severity\":\"error\",\"retryable\":false}}"
            else -> kotlin.error("Unknown stage error fixture")
        }
        return 422 to "{\"error\":{\"code\":\"FUTURE_OPERATION_FAILURE\",\"message\":\"internal diagnostic must not be displayed\"${if (fields.isEmpty()) "" else ",$fields"}}}"
    }
}
