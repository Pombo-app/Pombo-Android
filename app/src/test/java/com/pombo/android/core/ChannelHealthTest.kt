package com.pombo.android.core

import com.pombo.android.data.Channel
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Channel Health reads a channel against what its type requires, the same
 * way on both clients (web tests/unit/ChannelHealth.test.js): a false is a
 * finding, a null is a check the chain did not answer, a missing -1 is only
 * reported.
 */
class ChannelHealthTest {

    private val owner = "0x" + "ab".repeat(20)
    private val clone = "0x" + "cd".repeat(20)
    private val pombo = "0xae340e799e8151f6a4999d245e466197aa217667"
    private val id = "$owner/room-1"

    private fun channel(type: String = "public", readOnly: Boolean = false) = Channel(
        messageStreamId = id, ephemeralStreamId = "", adminStreamId = "", name = "room", type = type, readOnly = readOnly
    )
    private fun gated(wire: String = "sealed", exposure: String = "hidden") =
        channel("gated").copy(gateAddress = clone, wireIdentity = wire, exposure = exposure)

    private fun grantsOf(e: ChannelHealth.Expected, kind: String) = e.streams.first { it.kind == kind }.grants

    @Test
    fun `a public channel has four streams with public grants, and read-only takes publish off the -1 only`() {
        val e = ChannelHealth.expected(channel())
        assertEquals(listOf("message", "ephemeral", "admin", "interactions"), e.streams.map { it.kind })
        assertEquals(listOf(ChannelHealth.Grant(null, listOf("subscribe", "publish"))), grantsOf(e, "message"))
        val ro = ChannelHealth.expected(channel(readOnly = true))
        assertEquals(listOf(ChannelHealth.Grant(null, listOf("subscribe"))), grantsOf(ro, "message"))
        assertEquals(listOf(ChannelHealth.Grant(null, listOf("subscribe", "publish"))), grantsOf(ro, "interactions"))
    }

    @Test
    fun `a Sealed clone only reads on the -1, -2 and -5, a Visible one reads and writes`() {
        val sealed = ChannelHealth.expected(gated())
        for (k in listOf("message", "ephemeral", "interactions")) {
            assertEquals(listOf(ChannelHealth.Grant(clone, listOf("subscribe"))), grantsOf(sealed, k))
        }
        assertEquals(listOf(ChannelHealth.Grant(clone, listOf("subscribe", "publish"))), grantsOf(sealed, "keys"))
        assertEquals(listOf(ChannelHealth.Grant(clone, listOf("subscribe", "publish"))),
            grantsOf(ChannelHealth.expected(gated("visible")), "message"))
    }

    @Test
    fun `a listed gated channel lets the public read the -3, a hidden one does not`() {
        assertEquals(2, grantsOf(ChannelHealth.expected(gated(exposure = "visible")), "admin").size)
        assertEquals(1, grantsOf(ChannelHealth.expected(gated()), "admin").size)
    }

    /** What the bridge's channelStreamFacts would answer. */
    private fun facts(
        e: ChannelHealth.Expected,
        missing: Set<String> = emptySet(),
        unreadable: Set<String> = emptySet(),
        grantsOk: (String) -> Boolean = { true },
        nodes: (String) -> List<String>? = { listOf("0xnode") },
        days: (String) -> Int? = { 180 },
        desc: (String) -> JSONObject? = { null }
    ) = JSONObject().put("streams", JSONArray().apply {
        e.streams.forEach { s ->
            val suffix = s.id.takeLast(2)
            val f = JSONObject().put("id", s.id)
            when (suffix) {
                in missing -> f.put("exists", false)
                in unreadable -> f.put("exists", JSONObject.NULL)
                else -> {
                    f.put("exists", true).put("partitions", s.partitions)
                        .put("description", (desc(suffix) ?: if (s.linked) JSONObject().put("a", "pombo").put("ln", id)
                            .apply { s.metaKind?.let { put("k", it) } } else JSONObject().put("a", "pombo")).toString())
                        .put("grantsOk", grantsOk(suffix))
                    if (s.stored) {
                        val n = nodes(suffix)
                        f.put("nodes", n?.let { JSONArray(it) } ?: JSONObject.NULL).put("storageDays", days(suffix) ?: JSONObject.NULL)
                    }
                }
            }
            put(f)
        }
    })

    private fun report(ch: Channel, build: (ChannelHealth.Expected) -> JSONObject, gateReadOnly: Boolean? = false, passwordChecked: Boolean? = null) =
        ChannelHealth.expected(ch).let { e ->
            ChannelHealth.evaluate(id, e, build(e), passwordChecked, null,
                if (e.gate != null) gateReadOnly != null else null, gateReadOnly)
        }

    private fun steps(r: ChannelHealth.Report) = ChannelHealth.repairSteps(r, pombo).map { s ->
        "${s.step}:${s.kind}" + (s.node?.let { "@$it" } ?: "")
    }

    @Test
    fun `a healthy public channel has nothing to report or repair`() {
        val r = report(channel(), { facts(it) })
        assertEquals(ChannelHealth.Findings(emptyList(), emptyList(), emptyList()), ChannelHealth.findings(r))
        assertTrue(steps(r).isEmpty())
    }

    @Test
    fun `a missing -5 is one issue, created, granted and stored like the -1`() {
        val r = report(channel(), { facts(it, missing = setOf("-5")) })
        assertEquals(listOf("-5 stream"), ChannelHealth.findings(r).issues)
        assertEquals(listOf("create:interactions", "grants:interactions", "storage:interactions@0xnode"), steps(r))
    }

    @Test
    fun `a -5 with no storage node is a storage issue`() {
        val r = report(channel(), { facts(it, nodes = { s -> if (s == "-5") emptyList() else listOf("0xnode") }) })
        assertEquals(listOf("-5 storage"), ChannelHealth.findings(r).issues)
        assertEquals(listOf("storage:interactions@0xnode"), steps(r))
    }

    @Test
    fun `with no storage on the -1, the stored streams get the Pombo node for 180 days`() {
        val r = report(channel(), { facts(it, nodes = { emptyList() }, days = { null }) })
        val s = ChannelHealth.repairSteps(r, pombo).filter { it.step == "storage" }
        assertEquals(listOf("message", "admin", "interactions"), s.map { it.kind })
        assertTrue(s.all { it.node == pombo && it.days == 180 })
    }

    @Test
    fun `what could not be read is not checked and never repaired`() {
        val r = report(channel(), { facts(it, unreadable = setOf("-3"), nodes = { s -> if (s == "-5") null else listOf("0xnode") }) })
        val f = ChannelHealth.findings(r)
        assertTrue(f.issues.isEmpty())
        assertEquals(listOf("-3 stream", "-5 storage"), f.unchecked)
        assertTrue(steps(r).isEmpty())
    }

    @Test
    fun `a missing -1 is only reported`() {
        val r = report(channel(), { facts(it, missing = setOf("-1")) })
        assertEquals(listOf("-1 stream missing"), ChannelHealth.findings(r).reported)
        assertTrue(steps(r).isEmpty())
    }

    @Test
    fun `a retention that differs from the -1 is repaired to the -1's`() {
        val r = report(channel(), { facts(it, days = { s -> if (s == "-3") 30 else 180 }) })
        assertEquals(listOf("-3 retention"), ChannelHealth.findings(r).issues)
        assertEquals(listOf("retention:admin"), steps(r))
    }

    @Test
    fun `the Sealed clone publish grant and a read-only flag that disagrees with the gate are repaired`() {
        val r = report(gated(), { facts(it, grantsOk = { s -> s != "-1" },
            desc = { s -> if (s == "-1") JSONObject().put("a", "pombo").put("r", true) else null }) })
        assertEquals(listOf("-1 permissions", "read-only flag"), ChannelHealth.findings(r).issues)
        assertEquals(listOf("grants:message", "readOnly:message"), steps(r))
    }

    @Test
    fun `a Protected channel without the password cannot check the challenge`() {
        val r = report(channel("password"), { facts(it) }, passwordChecked = false)
        assertEquals(listOf("password challenge"), ChannelHealth.findings(r).unchecked)
    }

    @Test
    fun `a repair is charged step by step, and gated grants cost a batch`() {
        val r = report(gated(), { facts(it, missing = setOf("-5")) })
        val units = GasEstimator.channelRepairUnits(ChannelHealth.repairSteps(r, pombo))
        assertEquals(420_000L + 210_000L + 165_000L + 50_000L, units)
    }
}
