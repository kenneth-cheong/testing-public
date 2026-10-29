package io.github.kennethcheong.clauderemote.phone

import io.github.kennethcheong.clauderemote.data.HttpResult
import io.github.kennethcheong.clauderemote.data.Relay
import io.github.kennethcheong.clauderemote.data.SessionSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChangesTest {
    private fun s(id: String, status: String) = SessionSummary(id = id, title = id, status = status)

    @Test
    fun firstPollNeverReportsFinished() {
        val c = changes(null, listOf(s("a", "done"), s("b", "waiting_approval")))
        assertEquals(emptyList<SessionSummary>(), c.finished)
        assertEquals(listOf("b"), c.waiting.map { it.id })
        assertFalse(c.any)
    }

    @Test
    fun runningToDoneOrErrorIsFinished() {
        val c = changes(
            mapOf("a" to "running", "b" to "waiting_approval", "c" to "running"),
            listOf(s("a", "done"), s("b", "error"), s("c", "running")),
        )
        assertEquals(listOf("a", "b"), c.finished.map { it.id })
        assertTrue(c.any)
    }

    @Test
    fun aNewSessionAlreadyDoneFinishedBetweenPolls() {
        val c = changes(mapOf("a" to "idle"), listOf(s("a", "idle"), s("b", "done"), s("c", "idle")))
        assertEquals(listOf("b"), c.finished.map { it.id })
    }

    @Test
    fun doneStayingDoneOrIdleAfterRestartIsNotFinished() {
        val c = changes(mapOf("a" to "done", "b" to "running"), listOf(s("a", "done"), s("b", "idle")))
        assertEquals(emptyList<SessionSummary>(), c.finished)
        assertTrue(c.any)
    }

    @Test
    fun nothingChangedMeansNoRefresh() {
        assertFalse(changes(mapOf("a" to "idle"), listOf(s("a", "idle"))).any)
    }

    @Test
    fun newOrVanishedSessionsCountAsChanges() {
        assertTrue(changes(mapOf("a" to "idle"), listOf(s("a", "idle"), s("b", "running"))).any)
        assertTrue(changes(mapOf("a" to "idle", "b" to "idle"), listOf(s("a", "idle"))).any)
    }

    @Test
    fun alertTextLosesMarkdownMarks() {
        assertEquals("Status\n1. Just did: ran npm test", Watcher.plain("## Status\n1. **Just did:** ran `npm test`"))
    }

    @Test
    fun relayPayloadsSurviveTheRoundTrip() {
        val result = HttpResult(502, """{"error":"Phone can't reach x"}""", relayFailed = true)
        assertEquals(result, Relay.decode<HttpResult>(Relay.encode(result)))
        val long = "é".repeat(50_000)
        assertEquals(long, Relay.unpack(Relay.pack(long)))
    }
}
