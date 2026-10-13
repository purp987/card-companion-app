package com.cardprice.app.data.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest

class AppUpdatesTest {
    @get:Rule val tmp = TemporaryFolder()

    private val apkBytes = ByteArray(300_000) { (it % 251).toByte() }
    private val apkSha = MessageDigest.getInstance("SHA-256").digest(apkBytes).joinToString("") { "%02x".format(it) }

    private fun release(sha: String = apkSha, versionCode: Long = 5, minSdk: Int? = 26) =
        AppRelease(2, "com.cardprice.app.beta", versionCode, "2.1-beta", apkBytes.size.toLong(), sha, minSdk, "OTA updates", null, "/api/app/update/apk/2")

    private val checkJson = """{"available":true,"current":4,"latest":{"id":2,"appId":"com.cardprice.app.beta","versionCode":5,"versionName":"2.1-beta","size":34400000,"sha256":"ab12","minSdk":26,"notes":"What's new","publishedAt":"2026-10-13 04:10:00","url":"/api/app/update/apk/2"}}"""

    @Test
    fun checkSendsAppIdAndVersionWithToken() {
        val server = FakeServer().json("GET", "/api/app/update") { checkJson }
        val r = server.api().checkUpdate("com.cardprice.app.beta", 4)
        val call = server.calls.single()
        assertEquals("/api/app/update?appId=com.cardprice.app.beta&versionCode=4", call.path)
        assertEquals("Bearer tok", call.headers["Authorization"])
        assertTrue(r.available)
        assertEquals(5L, r.latest!!.versionCode)
        assertEquals("What's new", r.latest!!.notes)
        assertEquals("/api/app/update/apk/2", r.latest!!.url)
    }

    @Test
    fun nothingPublishedMeansNoUpdate() {
        val r = AppUpdates.parse("""{"available":false,"current":4,"latest":null}""")
        assertNull(r.latest)
        assertFalse(AppUpdates.isUpdate(r, 4, 34))
    }

    @Test
    fun onlyStrictlyNewerCompatibleBuildsCount() {
        fun check(r: AppRelease) = UpdateCheck(true, 4, r)
        assertTrue(AppUpdates.isUpdate(check(release(versionCode = 5)), 4, 34))
        assertFalse(AppUpdates.isUpdate(check(release(versionCode = 4)), 4, 34))
        assertFalse(AppUpdates.isUpdate(check(release(versionCode = 3)), 4, 34))
        assertFalse(AppUpdates.isUpdate(check(release(minSdk = 35)), 4, 34))
    }

    @Test
    fun automaticChecksRunEverySixHours() {
        val h = 3_600_000L
        assertTrue(AppUpdates.shouldCheck(null, 10 * h))
        assertFalse(AppUpdates.shouldCheck(10 * h, 15 * h))
        assertTrue(AppUpdates.shouldCheck(10 * h, 16 * h))
        assertTrue(AppUpdates.shouldCheck(10 * h, 5 * h)) // clock went backwards
    }

    private fun opener(bytes: ByteArray, code: Int = 200, header: String? = apkSha, seen: MutableList<Map<String, String>>? = null) =
        StreamOpener { _, headers ->
            seen?.add(headers)
            StreamResponse(code, bytes.size.toLong(), header, ByteArrayInputStream(bytes))
        }

    private fun api(opener: StreamOpener) = VaultApi({ FakeServer.BASE }, { "tok" }, FakeServer(), opener)

    @Test
    fun downloadVerifiesChecksumAndReportsProgress() {
        val seen = mutableListOf<Map<String, String>>()
        val dest = File(tmp.root, "updates/a.apk")
        var last = 0L
        api(opener(apkBytes, seen = seen)).downloadApk(release(), dest) { done, total -> last = done; assertEquals(apkBytes.size.toLong(), total) }
        assertEquals(apkBytes.size.toLong(), last)
        assertEquals(apkSha, AppUpdates.sha256Hex(dest))
        assertEquals("Bearer tok", seen.single()["Authorization"])
    }

    @Test
    fun corruptedDownloadIsDeleted() {
        val dest = File(tmp.root, "b.apk")
        val corrupted = apkBytes.copyOf().also { it[1000] = 42 }
        assertThrows(BadDownloadException::class.java) { api(opener(corrupted, header = null)).downloadApk(release(), dest) }
        assertFalse(dest.exists())
    }

    @Test
    fun serverChecksumMustMatchTheRelease() {
        val dest = File(tmp.root, "c.apk")
        assertThrows(BadDownloadException::class.java) { api(opener(apkBytes, header = "00ff")).downloadApk(release(), dest) }
        assertFalse(dest.exists())
    }

    @Test
    fun downloadErrorsAreTyped() {
        val dest = File(tmp.root, "d.apk")
        assertThrows(VaultException.Unauthorized::class.java) {
            api(opener("""{"error":"Unknown device"}""".toByteArray(), code = 401)).downloadApk(release(), dest)
        }
        assertThrows(VaultException.Offline::class.java) {
            api { _, _ -> throw IOException("down") }.downloadApk(release(), dest)
        }
        assertFalse(dest.exists())
    }
}
