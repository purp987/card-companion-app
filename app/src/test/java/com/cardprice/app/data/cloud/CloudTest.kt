package com.cardprice.app.data.cloud

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CloudTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun serverAddressesMustBeHttps() {
        assertEquals("https://cards.example.com", CloudUrls.normalize(" cards.example.com/ ", allowLocalHttp = false))
        assertEquals("https://cards.example.com:8443", CloudUrls.normalize("https://cards.example.com:8443", false))
        assertNull(CloudUrls.normalize("http://cards.example.com", false))
        assertNull(CloudUrls.normalize("ftp://cards.example.com", false))
        assertNull(CloudUrls.normalize("", false))
        assertNull(CloudUrls.normalize("cards example com", false))
        // Credentials, queries and fragments in the address are refused.
        assertNull(CloudUrls.normalize("https://user:pw@cards.example.com", false))
        assertNull(CloudUrls.normalize("https://cards.example.com/?x=1", false))
    }

    @Test
    fun onlyBetaBuildsMayUsePlainHttpToATestServer() {
        assertNull(CloudUrls.normalize("http://localhost:8080", allowLocalHttp = false))
        assertEquals("http://localhost:8080", CloudUrls.normalize("http://localhost:8080", allowLocalHttp = true))
        assertEquals("http://10.0.2.2:8080", CloudUrls.normalize("http://10.0.2.2:8080", allowLocalHttp = true))
        // Even in beta, plain HTTP to anywhere else is refused.
        assertNull(CloudUrls.normalize("http://192.168.0.5:8080", allowLocalHttp = true))
        assertNull(CloudUrls.normalize("http://cards.example.com", allowLocalHttp = true))
    }

    @Test
    fun backupHoldsTheAppDataButNoSecrets() {
        val files = tmp.newFolder("files")
        File(files, "collection").mkdirs()
        File(files, "collection/collection.json").writeText(
            """{"owned":{"ENGLISH|me05|me05-001|normal":3,"ENGLISH|me05|me05-002|holo":2},"progress":[{"setId":"me05"}]}""",
        )
        File(files, "collection/scan-learning.json").writeText("""{"readings":{}}""")
        File(files, "collection/scan-history.json").writeText("""[{"at":1,"cardId":"me05-001"}]""")
        File(files, "inventory").mkdirs()
        File(files, "inventory/inventory.json").writeText("""{"version":1,"items":[{"id":"a"}]}""")
        val prefs = mapOf("card_pricer" to mapOf("favorites" to setOf("me5"), "language" to "ENGLISH"))

        val bundle = BackupBundle.build(files, prefs, "1.2-beta", now = 42)
        assertEquals(BackupBundle.FORMAT, bundle.getString("format"))
        assertEquals(3, bundle.getJSONObject("collection").getJSONObject("owned").getInt("ENGLISH|me05|me05-001|normal"))
        assertTrue(bundle.has("scanLearning"))
        assertEquals("me05-001", bundle.getJSONArray("scanHistory").getJSONObject(0).getString("cardId")) // a list, not an object
        assertFalse(bundle.has("scanNotes")) // files that don't exist are left out
        assertEquals("me5", bundle.getJSONObject("settings").getJSONObject("card_pricer").getJSONArray("favorites").getString(0))
        assertFalse(bundle.toString().contains("token"))
        assertEquals(mapOf("cards" to 5, "sets" to 1, "inventory" to 1), BackupBundle.summary(bundle))
        assertNull(BackupBundle.validate(bundle))
        assertNotNull(BackupBundle.inventoryJson(bundle))
    }

    @Test
    fun foreignOrNewerBackupsAreNotRestored() {
        assertNotNull(BackupBundle.validate(JSONObject().put("format", "something-else")))
        assertNotNull(BackupBundle.validate(JSONObject().put("format", BackupBundle.FORMAT).put("version", 99).put("collection", JSONObject())))
        assertNotNull(BackupBundle.validate(JSONObject().put("format", BackupBundle.FORMAT).put("version", 1)))
    }
}
