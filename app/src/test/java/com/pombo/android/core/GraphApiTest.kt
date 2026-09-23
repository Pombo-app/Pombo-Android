package com.pombo.android.core

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class GraphApiTest {

    private fun storageNode(id: String, metadata: String) = JSONObject().put("id", id).put("metadata", metadata)

    @Test
    fun `reads each storage node with its URLs, and the retention`() {
        val data = JSONObject().put("stream", JSONObject()
            .put("metadata", """{"partitions":3,"storageDays":30}""")
            .put("storageNodes", JSONArray()
                .put(storageNode("0xAB", """{"urls":["https://a.example",7]}"""))
                .put(storageNode("0xcd", "not json"))))

        assertEquals(
            GraphApi.StreamStorage(
                listOf(
                    StorageEndpoints.Node("0xab", listOf("https://a.example")),
                    StorageEndpoints.Node("0xcd", emptyList())
                ),
                30
            ),
            GraphApi.streamStorageIn(data)
        )
    }

    @Test
    fun `knows no nodes and no retention for a stream The Graph does not have`() {
        assertEquals(GraphApi.StreamStorage(emptyList(), null), GraphApi.streamStorageIn(JSONObject().put("stream", JSONObject.NULL)))
    }
}
