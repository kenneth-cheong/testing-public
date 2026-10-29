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
    val finished = now.filter { s ->
        val was = before[s.id] ?: return@filter false
        RunStatus.isBusy(was) && (s.status == RunStatus.DONE || s.status == RunStatus.ERROR)
    }
    val any = now.any { before[it.id] != it.status } || before.keys.any { id -> now.none { it.id == id } }
    return Changes(finished, now.filter { it.status == RunStatus.WAITING_APPROVAL }, any)
}
