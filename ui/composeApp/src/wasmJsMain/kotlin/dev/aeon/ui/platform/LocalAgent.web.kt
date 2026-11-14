package dev.aeon.ui.platform

private object NoLocalAgent : LocalAgentHost {
    override val available = false
    override suspend fun start(): String? = "이 기기에서는 에이전트를 실행할 수 없어요."
    override suspend fun pairingCode(): String? = null
}

actual fun localAgentHost(): LocalAgentHost = NoLocalAgent
