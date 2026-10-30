package ru.finassist.pf.e2e

import ru.finassist.pf.core.api.model.AssistantLimit
import ru.finassist.pf.core.api.model.MessageRole
import ru.finassist.pf.core.api.model.MessagesList

/** Public persisted history/quota and client transport evidence for the one closed process-death scenario. */
object StageProcessQuestionEvidence {
    fun verify(runId: String, messages: MessagesList, limit: AssistantLimit, count: (String) -> Int) {
        check(!messages.hasOlder) { "Process question history is incomplete" }
        val questions = messages.messages.filter { it.role == MessageRole.USER }
        check(questions.size == 1 && questions.single().text == "#FIN362_TOOLS FIN386 process-death $runId") { "Unexpected process question history" }
        check(limit.remaining == 4) { "Unexpected process question quota" }
        check(count("real_ask_accepted") == 1 && count("ask_response_held") == 1 && count("ask_response_released") == 0) { "Uncertain outcome was not preserved" }
    }
}
