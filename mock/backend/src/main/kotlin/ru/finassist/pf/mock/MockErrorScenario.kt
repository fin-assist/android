package ru.finassist.pf.mock

/** Closed safe controls: no arbitrary server text, diagnostic or action can be injected by the app. */
enum class MockErrorOperation { PROFILE_UPDATE, OPERATIONS_LIST, ASSISTANT_ASK, PHONE_START, ANALYTICS_DISMISS }
enum class MockErrorScenario { UNKNOWN_CODE, UNKNOWN_SEVERITY, INVALID_MESSAGE, LEGACY_RESPONSE, RETRY_FORBIDDEN, TERMINAL_FAILURE }
