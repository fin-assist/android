package ru.finassist.pf.core.common.error

/** Local observation only: VPN presence does not prove the route or the cause of a failed request. */
enum class ConnectionEnvironment { VPN, OTHER_NETWORK, NO_NETWORK, UNKNOWN }

/** Reads system connectivity without contacting a server. */
fun interface ConnectionEnvironmentReader {
    fun read(): ConnectionEnvironment
}

data class ConnectionFailureTexts(val title: String, val hint: String) {
    val message: String get() = "$title. $hint"

    companion object {
        fun forEnvironment(environment: ConnectionEnvironment): ConnectionFailureTexts = ConnectionFailureTexts(
            title = "Не удаётся подключиться к серверу",
            hint = if (environment == ConnectionEnvironment.VPN) {
                "Попробуйте отключить VPN и повторить подключение"
            } else {
                "Проверьте интернет и попробуйте ещё раз"
            },
        )
    }
}
