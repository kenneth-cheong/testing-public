package io.github.kennethcheong.clauderemote.phone

import io.github.kennethcheong.clauderemote.data.RunStatus
import io.github.kennethcheong.clauderemote.data.SessionSummary

/**
 * What one poll of a computer's sessions means, compared with the poll before.
 * [finished] ended (done or error) since last time; [waiting] are waiting for an approval now;
 * [any] says some session's state changed, so the watch should refresh.
 */
data class Changes(
    val finished: List<SessionSummary>,
    val waiting: List<SessionSummary>,
    val any: Boolean,
)

/** [before] is each session's status at the last poll, or null on the first poll (which never alerts). */
fun changes(before: Map<String, String>?, now: List<SessionSummary>): Changes {
    if (before == null) return Changes(emptyList(), now.filter { it.status == RunStatus.WAITING_APPROVAL }, any = false)
    // A session new since the last poll that is already done ran start to finish in between: a quick
    // prompt can finish before its transcript is even listed. Only bridge runs ever report done or error.
    val finished = now.filter { s ->
        val ended = s.status == RunStatus.DONE || s.status == RunStatus.ERROR
        val was = before[s.id]
        ended && (was == null || RunStatus.isBusy(was))
    }
    val any = now.any { before[it.id] != it.status } || before.keys.any { id -> now.none { it.id == id } }
    return Changes(finished, now.filter { it.status == RunStatus.WAITING_APPROVAL }, any)
}
