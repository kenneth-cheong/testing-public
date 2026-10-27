package io.github.kennethcheong.clauderemote.data

import kotlinx.serialization.Serializable

/** Run state the bridge reports for a session. */
object RunStatus {
    const val IDLE = "idle"
    const val RUNNING = "running"
    const val WAITING_APPROVAL = "waiting_approval"
    const val DONE = "done"
    const val ERROR = "error"

    fun isBusy(status: String) = status == RUNNING || status == WAITING_APPROVAL
}

@Serializable
data class SessionSummary(
    val id: String,
    val title: String,
    val cwd: String? = null,
    val lastModified: Long = 0,
    val status: String = RunStatus.IDLE,
)

@Serializable
data class ChatLine(
    /** "user", "assistant" or "tool". */
    val role: String,
    val text: String,
)

@Serializable
data class PendingApproval(
    val id: String,
    val tool: String,
    val summary: String,
)

@Serializable
data class SessionDetail(
    val id: String,
    val title: String,
    val status: String = RunStatus.IDLE,
    val reply: String? = null,
    val error: String? = null,
    val pending: PendingApproval? = null,
    val messages: List<ChatLine> = emptyList(),
)

@Serializable
internal data class CreatedSession(val id: String)

@Serializable
internal data class TextBody(val text: String)

@Serializable
internal data class ApprovalBody(val approvalId: String, val allow: Boolean)

@Serializable
internal data class PairBody(val code: String, val name: String)

@Serializable
internal data class PairResult(val token: String)

@Serializable
internal data class ErrorBody(val error: String)
