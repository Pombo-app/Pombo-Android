package com.pombo.android.core

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A Pombo storage node serves a gated channel's streams and a DM inbox only to
 * a signed read, so a wake for one of them is verifiable only with the account
 * key; public and password channels stay anonymous. These tests pin which
 * requests carry a signature, and which answers are worth asking a second node
 * about.
 */
class PushVerifierTest {

    private val row = """[{"timestamp":2000,"publisherId":"0xABC","content":{"type":"text","text":"hi"}}]"""

    private fun entry(type: String, last: Long = 1000L) = PushRegistry.Entry(
        streamId = "0xowner/channel-1", tag = "a1", type = type, name = "C", lastTimestamp = last
    )

    private class Recorder(
        private val answers: List<PushVerifier.Response>,
        private val throwOn: Set<Int> = emptySet()
    ) {
        val urls = mutableListOf<String>()
        val headers = mutableListOf<Map<String, String>>()
        fun call(url: String, h: Map<String, String>): PushVerifier.Response {
            val i = urls.size
            urls.add(url)
            headers.add(h)
            if (i in throwOn) throw java.io.IOException("connection reset")
            return answers.getOrElse(i) { PushVerifier.Response(500, null) }
        }
    }

    private fun verifier(
        recorder: Recorder,
        endpoints: List<String> = listOf("https://a.test", "https://b.test"),
        sign: (String) -> Map<String, String>? = { mapOf("x-pombo-user" to "0xme") }
    ) = PushVerifier(
        endpointsFor = { endpoints },
        signHeaders = sign,
        http = recorder::call
    )

    @Test
    fun `gated read carries the signature headers`() = runBlocking {
        val rec = Recorder(listOf(PushVerifier.Response(200, row)))
        val result = verifier(rec).verify(entry("gated"))

        assertTrue(result.hasNew)
        assertEquals(2000L, result.timestamp)
        assertEquals("0xABC", result.publisherId)
        assertEquals("hi", result.content?.optString("text"))
        assertEquals(mapOf("x-pombo-user" to "0xme"), rec.headers.single())
        assertTrue(rec.urls.single().startsWith("https://a.test/streams/"))
        assertTrue(rec.urls.single().endsWith("/data/partitions/0/last?count=1"))
    }

    @Test
    fun `dm inbox read carries the signature headers`() = runBlocking {
        val rec = Recorder(listOf(PushVerifier.Response(200, row)))
        verifier(rec).verify(entry("dm-inbox"))
        assertEquals(mapOf("x-pombo-user" to "0xme"), rec.headers.single())
    }

    @Test
    fun `public and password reads stay anonymous`() = runBlocking {
        for (type in listOf("public", "password")) {
            val rec = Recorder(listOf(PushVerifier.Response(200, row)))
            verifier(rec).verify(entry(type))
            assertTrue("$type must not be signed", rec.headers.single().isEmpty())
        }
    }

    @Test
    fun `without a key nothing is asked at all`() = runBlocking {
        val rec = Recorder(listOf(PushVerifier.Response(200, row)))
        val result = verifier(rec, sign = { null }).verify(entry("gated"))

        assertFalse(result.hasNew)
        assertTrue(rec.urls.isEmpty())
    }

    @Test
    fun `a refusal is final, not a node to ask again`() = runBlocking {
        for (code in listOf(401, 403, 404)) {
            val rec = Recorder(listOf(PushVerifier.Response(code, null)))
            val result = verifier(rec).verify(entry("gated"))

            assertFalse(result.hasNew)
            assertEquals("HTTP $code must not be retried elsewhere", 1, rec.urls.size)
        }
    }

    @Test
    fun `a node that failed as a node is asked again at the next url`() = runBlocking {
        val rec = Recorder(listOf(PushVerifier.Response(503, null), PushVerifier.Response(200, row)))
        val result = verifier(rec).verify(entry("gated"))

        assertTrue(result.hasNew)
        assertEquals(2, rec.urls.size)
        assertTrue(rec.urls[1].startsWith("https://b.test/"))
    }

    @Test
    fun `an unreachable node is asked again at the next url`() = runBlocking {
        val rec = Recorder(listOf(PushVerifier.Response(200, row), PushVerifier.Response(200, row)), throwOn = setOf(0))
        val result = verifier(rec).verify(entry("gated"))

        assertTrue(result.hasNew)
        assertEquals(2, rec.urls.size)
    }

    @Test
    fun `nothing newer than the watermark is a false positive`() = runBlocking {
        val rec = Recorder(listOf(PushVerifier.Response(200, row)))
        val result = verifier(rec).verify(entry("gated", last = 2000L))

        assertFalse(result.hasNew)
        assertEquals(2000L, result.timestamp)
        assertEquals(null, result.content)
    }

    @Test
    fun `resolved endpoints are the ones asked`() = runBlocking {
        val rec = Recorder(listOf(PushVerifier.Response(200, row)))
        verifier(rec, endpoints = listOf("https://own.provider.test")).verify(entry("public"))

        assertTrue(rec.urls.single().startsWith("https://own.provider.test/streams/"))
    }

    // ---- a channel whose storage moved ----

    private val older = """[{"timestamp":1000,"publisherId":"0xABC","content":{"type":"text","text":"old"}}]"""

    private class Chain(val answer: List<String>) {
        var asked = 0
        val remembered = mutableListOf<List<String>>()
    }

    private fun moved(
        rec: Recorder, chain: Chain, stored: List<String>, checkedAt: Long = 0L,
        sign: (String) -> Map<String, String>? = { mapOf("x-pombo-user" to "0xme") }
    ) = PushVerifier(
        endpointsFor = { stored },
        signHeaders = sign,
        http = rec::call,
        resolveProviders = { chain.asked++; chain.answer },
        providersCheckedAt = { checkedAt },
        rememberProviders = { _, urls -> chain.remembered += urls }
    )

    @Test
    fun `asks the providers the chain has now when the registered ones have nothing new`() = runBlocking {
        val rec = Recorder(listOf(PushVerifier.Response(200, older), PushVerifier.Response(200, row)))
        val chain = Chain(listOf("https://new.test"))

        val result = moved(rec, chain, stored = listOf("https://old.test")).verify(entry("gated", last = 1500L))

        assertTrue(result.hasNew)
        assertTrue(rec.urls[1].startsWith("https://new.test/streams/"))
        assertEquals(listOf(listOf("https://new.test")), chain.remembered)
    }

    @Test
    fun `asks the chain when a registration has no providers at all`() = runBlocking {
        val rec = Recorder(listOf(PushVerifier.Response(200, row)))
        val chain = Chain(listOf("https://new.test"))

        val result = moved(rec, chain, stored = emptyList()).verify(entry("public"))

        assertTrue(result.hasNew)
        assertTrue(rec.urls.single().startsWith("https://new.test/streams/"))
    }

    @Test
    fun `does not ask the chain again within the refresh window`() = runBlocking {
        val rec = Recorder(listOf(PushVerifier.Response(200, older)))
        val chain = Chain(listOf("https://new.test"))

        val result = moved(rec, chain, stored = listOf("https://old.test"), checkedAt = System.currentTimeMillis())
            .verify(entry("gated", last = 1500L))

        assertFalse(result.hasNew)
        assertEquals(0, chain.asked)
    }

    @Test
    fun `keeps its answer when the providers have not changed`() = runBlocking {
        val rec = Recorder(listOf(PushVerifier.Response(200, older)))
        val chain = Chain(listOf("https://old.test"))

        val result = moved(rec, chain, stored = listOf("https://old.test")).verify(entry("gated", last = 1500L))

        assertFalse(result.hasNew)
        assertEquals(1, rec.urls.size)
        assertEquals(1, chain.asked)
    }

    @Test
    fun `asks the chain nothing while there is no key to sign`() = runBlocking {
        val rec = Recorder(emptyList())
        val chain = Chain(listOf("https://new.test"))

        val result = moved(rec, chain, stored = listOf("https://old.test"), sign = { null }).verify(entry("gated"))

        assertFalse(result.hasNew)
        assertEquals(0, chain.asked)
    }

    @Test
    fun `the chain's answer keeps only the URLs a client may reach`() {
        val node = { urls: String -> JSONObject().put("metadata", """{"urls":$urls}""") }
        val data = JSONObject().put("stream", JSONObject().put("storageNodes", org.json.JSONArray()
            .put(node("""["https://1.storage.test/","http://plain.test"]"""))
            .put(node("""["https://10.0.0.1","https://2.storage.test"]"""))
            .put(JSONObject().put("metadata", "not json"))))

        assertEquals(listOf("https://1.storage.test", "https://2.storage.test"), GraphApi.storageNodeUrlsIn(data))
    }

    @Test
    fun `a closed channel says a message arrived and nothing more`() {
        val text = org.json.JSONObject("""{"type":"text","text":"secret"}""")
        assertEquals("New message", PushVerifier.preview("gated", null))
        assertEquals("New message", PushVerifier.preview("gated", text))
    }

    @Test
    fun `an opened direct message shows its text`() {
        val text = org.json.JSONObject("""{"type":"text","text":"are you around?"}""")
        assertEquals("are you around?", PushVerifier.preview("dm-inbox", text))
        assertEquals("📷 Image", PushVerifier.preview("dm-inbox", org.json.JSONObject("""{"type":"image"}""")))
    }

    @Test
    fun `a direct message that never opened stays generic`() {
        val envelope = org.json.JSONObject("""{"v":2,"epk":"0x02ab","e":"aes-256-gcm","ct":"dead"}""")
        assertEquals("You have a new message", PushVerifier.preview("dm-inbox", envelope))
        assertEquals("You have a new message", PushVerifier.preview("dm-inbox", null))
        assertEquals("You have a new message", PushVerifier.preview("dm", null))
    }

    @Test
    fun `a public channel still shows what was said`() {
        val text = org.json.JSONObject("""{"type":"text","text":"hello everyone"}""")
        assertEquals("hello everyone", PushVerifier.preview("public", text))
        assertEquals("New message", PushVerifier.preview("public", null))
    }
}
