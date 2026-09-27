package com.cardprice.app.data.collection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CollectionBackupTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun store() = CollectionStore(tmp.root, tmp.newFolder())
    private val hour = CollectionStore.SNAPSHOT_INTERVAL_MS
    private val mainFile get() = File(tmp.root, "collection/collection.json")

    @Test
    fun backupHoldsTheCollectionFromBeforeTheChange() {
        val s = store()
        s.save(mapOf("a" to 1), emptyList(), now = 0) // first save: nothing to back up yet
        assertTrue(s.backups().isEmpty())
        s.save(mapOf("a" to 1, "test" to 3), emptyList(), now = 10)
        assertEquals(listOf(1), s.backups().map { it.copies }) // the version before "test" was added
        s.save(mapOf("a" to 1), emptyList(), now = 20)
        assertEquals(1, s.backups().size) // at most one an hour
        s.save(mapOf("a" to 2), emptyList(), now = 10 + hour)
        assertEquals(listOf(1, 1), s.backups().map { it.copies })
    }

    @Test
    fun keepsTwentyNewest() {
        val s = store()
        repeat(25) { i -> s.save(mapOf("a" to i + 1), emptyList(), now = i * hour) }
        val backups = s.backups()
        assertEquals(CollectionStore.MAX_BACKUPS, backups.size)
        assertEquals(24, backups.first().copies) // the version replaced by the 25th save
    }

    @Test
    fun deletedMainFileIsRestoredFromNewestBackup() {
        val s = store()
        val mine = mapOf("ENGLISH|me05|me05-111|holo" to 2, "ENGLISH|me05|me05-114|holo" to 1)
        s.save(mine, emptyList(), now = 0)
        s.save(mine + ("ENGLISH|me05|me05-065|holo" to 1), emptyList(), now = 1)
        assertTrue(mainFile.delete())

        val loaded = store().load()
        assertEquals(mine, loaded.owned)
        assertNotNull(loaded.restoredFrom)
        assertTrue(mainFile.exists())
    }

    @Test
    fun damagedMainFileIsSetAsideAndBackupUsed() {
        val s = store()
        s.save(mapOf("x" to 1), emptyList(), now = 0)
        s.save(mapOf("x" to 2), emptyList(), now = 1)
        mainFile.writeText("{ not json")
        val loaded = store().load()
        assertEquals(mapOf("x" to 1), loaded.owned)
        assertTrue(File(tmp.root, "collection").list()!!.any { it.startsWith("collection.damaged-") })
    }

    @Test
    fun normalLoadDoesNotReportRestore() {
        val s = store()
        s.save(mapOf("x" to 1), emptyList(), now = 0)
        assertNull(store().load().restoredFrom)
    }

    @Test
    fun restoringABackupKeepsTheCurrentCollectionAsABackup() {
        val s = store()
        s.save(mapOf("old" to 1), emptyList(), now = 0)
        s.save(mapOf("old" to 1, "new" to 5), emptyList(), now = 1) // backs up {"old": 1}
        val old = s.backups().single()
        val restored = s.restore(old, now = 2)!!
        assertEquals(mapOf("old" to 1), restored.owned)
        assertEquals(mapOf("old" to 1), store().load().owned)
        // The collection we replaced is now a backup too.
        assertTrue(s.backups().any { it.copies == 6 })
    }

    @Test
    fun restorePointsArePinnedAndSurviveRotation() {
        val s = store()
        val manual = mapOf(
            "ENGLISH|me05|me05-065|holo" to 1, "ENGLISH|me05|me05-088|holo" to 1, "ENGLISH|me05|me05-094|holo" to 1,
            "ENGLISH|me05|me05-095|holo" to 1, "ENGLISH|me05|me05-097|holo" to 1, "ENGLISH|me05|me05-107|holo" to 1,
            "ENGLISH|me05|me05-108|holo" to 1, "ENGLISH|me05|me05-111|holo" to 1, "ENGLISH|me05|me05-114|holo" to 1,
        )
        s.save(manual, emptyList(), now = 0)
        val point = s.saveRestorePoint("Manual entries", now = 1)!!
        assertEquals("Manual entries", point.name)
        assertEquals(9, point.copies)

        // Lots of later saves rotate automatic backups but never the restore point.
        repeat(30) { i -> s.save(mapOf("x" to i + 1), emptyList(), now = (i + 1) * hour) }
        assertEquals(CollectionStore.MAX_BACKUPS, s.backups().size)
        assertEquals(listOf("Manual entries"), s.restorePoints().map { it.name })

        assertEquals(manual, s.restore(s.restorePoints().single())!!.owned)
        assertEquals(manual, store().load().owned)
    }

    @Test
    fun noRestorePointWithoutACollection() {
        assertNull(store().saveRestorePoint("Empty"))
    }
}
