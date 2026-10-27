package io.github.kennethcheong.clauderemote.data

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

class BridgeClientTest {
    private val server = MockWebServer()
    private lateinit var client: BridgeClient

    @Before
    fun setUp() {
        server.start()
        client = BridgeClient(server.url("/").toString(), "secret-token")
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun listSessionsSendsTokenAndParsesMissingFields() = runTest {
        server.enqueue(MockResponse().setBody("""[{"id":"a","title":"Fix build","lastModified":5,"status":"running","extra":1}]"""))

        val sessions = client.listSessions(limit = 5)

        assertEquals(listOf(SessionSummary(id = "a", title = "Fix build", lastModified = 5, status = "running")), sessions)
        val request = server.takeRequest()
        assertEquals("/sessions?limit=5", request.path)
        assertEquals("Bearer secret-token", request.getHeader("Authorization"))
    }

    @Test
    fun templatesAreFetchedWithToken() = runTest {
        server.enqueue(MockResponse().setBody("""[{"label":"Status","text":"What's left?"}]"""))

        val templates = client.templates()

        assertEquals(listOf(PromptTemplate("Status", "What's left?")), templates)
        val request = server.takeRequest()
        assertEquals("/templates", request.path)
        assertEquals("Bearer secret-token", request.getHeader("Authorization"))
    }

    @Test
    fun sessionParsesPendingApproval() = runTest {
        server.enqueue(MockResponse().setBody(
            """{"id":"a","title":"t","status":"waiting_approval","pending":{"id":"p","tool":"Bash","summary":"Bash: ls"},
               "messages":[{"role":"user","text":"hi"}]}"""
        ))

        val detail = client.session("a")

        assertEquals(PendingApproval("p", "Bash", "Bash: ls"), detail.pending)
        assertEquals(listOf(ChatLine("user", "hi")), detail.messages)
        assertNull(detail.reply)
    }

    @Test
    fun postsJsonBodies() = runTest {
        server.enqueue(MockResponse().setResponseCode(202).setBody("""{"id":"new-id"}"""))
        server.enqueue(MockResponse().setBody("""{"ok":true}"""))

        assertEquals("new-id", client.newSession("hello \"watch\""))
        client.answerApproval("new-id", "p1", allow = false)

        val create = server.takeRequest()
        assertEquals("POST", create.method)
        assertEquals("""{"text":"hello \"watch\""}""", create.body.readUtf8())
        val approval = server.takeRequest()
        assertEquals("/sessions/new-id/approval", approval.path)
        assertEquals("""{"approvalId":"p1","allow":false}""", approval.body.readUtf8())
    }

    @Test
    fun pairSendsCodeWithoutAuthorization() = runTest {
        server.enqueue(MockResponse().setBody("""{"token":"device-token","machine":"MacBook Pro"}"""))

        val result = BridgeClient(server.url("/").toString()).pair("123 456", "Pixel Watch")

        assertEquals(PairResult("device-token", "MacBook Pro"), result)
        val request = server.takeRequest()
        assertEquals("/pair", request.path)
        assertNull(request.getHeader("Authorization"))
        assertEquals("""{"code":"123 456","name":"Pixel Watch"}""", request.body.readUtf8())
    }

    @Test
    fun pairAcceptsOlderBridgeWithoutMachineName() = runTest {
        server.enqueue(MockResponse().setBody("""{"token":"device-token"}"""))

        val result = BridgeClient(server.url("/").toString()).pair("123456", "Pixel Watch")

        assertEquals(PairResult("device-token", null), result)
    }

    @Test
    fun infoReturnsMachineName() = runTest {
        server.enqueue(MockResponse().setBody("""{"machine":"MacBook Air"}"""))

        assertEquals(BridgeInfo("MacBook Air"), client.info())
        assertEquals("/info", server.takeRequest().path)
    }

    @Test
    fun revokedTokenTriggersOnUnauthorized() = runTest {
        var unauthorized = 0
        val revoked = BridgeClient(server.url("/").toString(), "old-token", onUnauthorized = { unauthorized++ })
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"Unauthorized"}"""))

        assertThrows(BridgeException::class.java) { kotlinx.coroutines.runBlocking { revoked.listSessions() } }
        assertEquals(1, unauthorized)
    }

    @Test
    fun errorsCarryTheBridgeMessage() = runTest {
        server.enqueue(MockResponse().setResponseCode(409).setBody("""{"error":"Session is busy"}"""))

        val error = assertThrows(BridgeException::class.java) {
            kotlinx.coroutines.runBlocking { client.sendMessage("a", "x") }
        }
        assertEquals(409, error.status)
        assertEquals("Session is busy", error.message)
    }
}
