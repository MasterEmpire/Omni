package com.omni.hub

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class AppTest {

    @Test
    fun testZipSlipProtectionLogic() {
        val baseDir = File("/data/user/0/com.omni.hub/app_plugins/sample_tool")
        val safeEntry = File(baseDir, "res/icon.png")
        val maliciousEntry = File(baseDir, "../../../system/bad.dex")

        assertTrue(safeEntry.canonicalPath.startsWith(baseDir.canonicalPath))
        assertFalse(maliciousEntry.canonicalPath.startsWith(baseDir.canonicalPath))
    }

    @Test
    fun testPluginMetadataSerialization() {
        val metadataJson = JSONObject().apply {
            put("id", "sample_tool")
            put("name", "Sample Utility")
            put("description", "Dynamic Utility Module")
            put("entryClass", "com.omni.plugin.SampleUtility")
            put("installedAt", 1720000000000L)
        }

        val array = JSONArray().put(metadataJson)
        assertEquals(1, array.length())

        val parsed = array.getJSONObject(0)
        assertEquals("sample_tool", parsed.getString("id"))
        assertEquals("com.omni.plugin.SampleUtility", parsed.getString("entryClass"))
    }
}