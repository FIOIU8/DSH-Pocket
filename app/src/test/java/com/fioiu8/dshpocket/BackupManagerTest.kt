package com.fioiu8.dshpocket

import com.fioiu8.dshpocket.backup.BackupManager
import com.fioiu8.dshpocket.backup.BackupManager.Manifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * The backup contract: credential-shaped files never enter an archive, a round trip
 * preserves the rest byte for byte, and a restore never destroys the current home on
 * a bad archive.
 */
class BackupManagerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val manifest = Manifest(
        schemaVersion = BackupManager.SCHEMA_VERSION,
        createdAt = "2026-10-25T01:00:00Z",
        appVersion = "1.0-test",
        dshVersion = "0.2.0-rc.2",
        fileCount = 0,
    )

    private fun home(): File {
        val home = tmp.newFolder("dsh-home")
        fun file(relative: String): File = File(home, relative).apply { parentFile?.mkdirs() }
        file("cordis.patch.yml").writeText("patch")
        file("sessions/s1.json").writeText("""{"id":"s1"}""")
        file("sessions/s2.json").writeText("""{"id":"s2"}""")
        file(".credentials.yaml").writeText("secret: true")
        file("credentials/store.json").writeText("secret: true")
        file("logs/boot.log").writeText("noise")
        file("keys/agent.key").writeText("private key")
        return home
    }

    private fun export(home: File): ByteArray {
        val out = ByteArrayOutputStream()
        BackupManager.export(home, out, manifest)
        return out.toByteArray()
    }

    private fun entryNames(archive: ByteArray): List<String> {
        val names = mutableListOf<String>()
        ZipInputStream(ByteArrayInputStream(archive)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                names += entry.name
            }
        }
        return names
    }

    // ---- exclusions ------------------------------------------------------------------------

    @Test
    fun `credential-shaped paths are excluded from the archive`() {
        val names = entryNames(export(home()))

        assertTrue(names.contains("sessions/s1.json"))
        assertFalse("credentials yaml stays out", names.contains(".credentials.yaml"))
        assertFalse("the credentials directory stays out", names.any { it.startsWith("credentials/") })
        assertFalse("key files stay out", names.any { it.endsWith(".key") })
        assertFalse("logs stay out", names.any { it.startsWith("logs/") })
    }

    @Test
    fun `the exclusion rule is conservative about what it lets through`() {
        assertTrue(BackupManager.isExcluded(".credentials.yaml"))
        assertTrue(BackupManager.isExcluded("credentials/store.json"))
        assertTrue(BackupManager.isExcluded("deep/nested/Credentials/x"))
        assertTrue(BackupManager.isExcluded(".ssh/id_rsa"))
        assertTrue(BackupManager.isExcluded("secrets/api.key"))
        assertTrue(BackupManager.isExcluded("logs/boot.log"))
        assertFalse(BackupManager.isExcluded("cordis.patch.yml"))
        assertFalse(BackupManager.isExcluded("sessions/s1.json"))
        assertFalse("an engine data file may itself be named like a log segment", BackupManager.isExcluded("sessions/logs.json"))
    }

    // ---- round trip ------------------------------------------------------------------------

    @Test
    fun `restore brings the exported files back and nothing else`() {
        val original = home()
        val restored = tmp.newFolder("restored")

        val outcome = BackupManager.restore(ByteArrayInputStream(export(original)), restored)

        assertTrue(outcome is BackupManager.RestoreOutcome.Done)
        assertEquals(3, (outcome as BackupManager.RestoreOutcome.Done).fileCount)
        assertEquals("patch", File(restored, "cordis.patch.yml").readText())
        assertEquals("""{"id":"s1"}""", File(restored, "sessions/s1.json").readText())
        assertEquals("""{"id":"s2"}""", File(restored, "sessions/s2.json").readText())
        assertFalse("restore must not resurrect excluded files", File(restored, ".credentials.yaml").exists())
    }

    @Test
    fun `a fresh install restores into an empty target`() {
        val restored = tmp.newFolder("fresh")

        val outcome = BackupManager.restore(ByteArrayInputStream(export(home())), restored)

        assertTrue(outcome is BackupManager.RestoreOutcome.Done)
        assertTrue(File(restored, "cordis.patch.yml").isFile)
    }

    // ---- bad archives ----------------------------------------------------------------------

    private fun archiveOf(vararg entries: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, text) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    @Test
    fun `an archive without a manifest is rejected`() {
        val target = tmp.newFolder("target")
        File(target, "precious.json").writeText("keep")

        val outcome = BackupManager.restore(ByteArrayInputStream(archiveOf("sessions/s1.json" to "{}")), target)

        assertEquals("sessions/s1.json first", BackupManager.RestoreOutcome.BadArchive("first entry is not dsh-pocket-backup.json"), outcome)
        assertEquals("the current home is untouched", "keep", File(target, "precious.json").readText())
    }

    @Test
    fun `a wrong schema version is rejected before anything moves`() {
        val target = tmp.newFolder("target2")
        val future = manifest.copy(schemaVersion = BackupManager.SCHEMA_VERSION + 1)

        val outcome = BackupManager.restore(
            ByteArrayInputStream(archiveOf(BackupManager.MANIFEST_ENTRY to future.toJson().toString())),
            target,
        )

        assertTrue(outcome is BackupManager.RestoreOutcome.BadArchive)
        assertTrue("no staging survived", File(target.parentFile, "dsh-home-restore-tmp").let { !it.exists() || it.listFiles().isEmpty() })
    }

    @Test
    fun `a corrupt manifest is rejected`() {
        val outcome = BackupManager.restore(
            ByteArrayInputStream(archiveOf(BackupManager.MANIFEST_ENTRY to "not json")),
            tmp.newFolder("target3"),
        )

        assertTrue(outcome is BackupManager.RestoreOutcome.BadArchive)
    }

    @Test
    fun `a traversal entry name is rejected`() {
        val outcome = BackupManager.restore(
            ByteArrayInputStream(
                archiveOf(
                    BackupManager.MANIFEST_ENTRY to manifest.toJson().toString(),
                    "../escaped.txt" to "boom",
                ),
            ),
            tmp.newFolder("target4"),
        )

        assertTrue(outcome is BackupManager.RestoreOutcome.BadArchive)
    }

    // ---- manifest --------------------------------------------------------------------------

    @Test
    fun `manifest json round-trips and tolerates a missing dsh version`() {
        val parsed = Manifest.fromJson(manifest.toJson().toString())

        assertEquals(manifest, parsed)

        val withoutDsh = manifest.copy(dshVersion = null)
        assertEquals(withoutDsh, Manifest.fromJson(withoutDsh.toJson().toString()))
        assertNull(Manifest.fromJson("{"))
    }
}
