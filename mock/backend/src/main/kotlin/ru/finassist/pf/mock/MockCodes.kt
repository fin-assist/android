package ru.finassist.pf.mock

import ru.finassist.pf.core.api.model.OperationKindFilter

/**
 * Codes the client does not know, sent by the fake server when [MockConfig.unknownCodes] is on: how UI tests reach the
 * fallbacks of open codes (FIN-82/83) — a new filter kind, a new import notice, a new analytics feature.
 */
object MockCodes {
    /** Means `expense` to the fake server's search. */
    val EXPENSE_UNKNOWN = OperationKindFilter("expense_e2e")
    const val NOTICE_UNKNOWN = "e2e_notice"
    const val NOTICE_UNKNOWN_TEXT = "Часть операций пришла из нового источника — проверьте категории"
    const val FEATURE_UNKNOWN = "e2e_feature"
    const val FEATURE_UNKNOWN_NAME = "Новая метрика"
    const val FEATURE_UNKNOWN_TEXT = "Новая метрика — нужно 6 полных месяцев"
}
