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

/** A canned prompt from the bridge's templates file. */
@Serializable
data class PromptTemplate(
    val label: String,
    val text: String,
)

@Serializable
internal data class CreatedSession(val id: String)

@Serializable
internal data class TextBody(val text: String)

@Serializable
internal data class ApprovalBody(val approvalId: String, val allow: Boolean)

@Serializable
internal data class PairBody(val code: String, val name: String)

/** [machine] is the bridge computer's short name, e.g. "MacBook Air"; older bridges omit it. */
@Serializable
data class PairResult(val token: String, val machine: String? = null)

@Serializable
data class BridgeInfo(val machine: String)

@Serializable
internal data class ErrorBody(val error: String)
