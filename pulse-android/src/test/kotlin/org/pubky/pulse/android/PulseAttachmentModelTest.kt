package org.pubky.pulse.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Pure-model tests for [PulseAttachment], mirroring Swift's `PulseAttachment` struct
 * (`Models/PulseAttachment.swift`):
 *  - `bytes(...)` builds a [PulseAttachment.Source.DataSource] holding the exact
 *    bytes; `name` is required, `contentType` defaults to null. Mirrors Swift's
 *    `init(data:name:contentType:)`.
 *  - `file(...)` builds a [PulseAttachment.Source.FileSource]; `name` defaults to
 *    the file's own name (Swift's `fileURL.lastPathComponent`) and can be
 *    overridden; `contentType` passes through. Mirrors `init(fileURL:name:contentType:)`.
 *  - `DataSource` provides structural `equals`/`hashCode` over its bytes (Swift's
 *    `Data` is a value type) — `ByteArray`'s default identity semantics would
 *    break dedup, so the SDK overrides them.
 *
 * No Android framework, no JSON — runs as a plain JVM unit test.
 */
class PulseAttachmentModelTest {

    @Test
    fun `bytes factory wraps a DataSource with the exact bytes`() {
        val raw = "hello attachment".toByteArray()
        val att = PulseAttachment.bytes(raw, name = "log.txt", contentType = "text/plain")

        assertEquals("log.txt", att.name)
        assertEquals("text/plain", att.contentType)
        val source = att.source
        assertTrue("bytes() must produce a DataSource", source is PulseAttachment.Source.DataSource)
        assertTrue(raw.contentEquals((source as PulseAttachment.Source.DataSource).bytes))
    }

    @Test
    fun `bytes factory defaults contentType to null`() {
        val att = PulseAttachment.bytes("x".toByteArray(), name = "a.bin")
        assertNull("contentType defaults to null so the uploader can infer it", att.contentType)
    }

    @Test
    fun `file factory defaults name to the file's own name`() {
        val file = File("/tmp/owl-screens/screenshot.png")
        val att = PulseAttachment.file(file)

        assertEquals("screenshot.png", att.name)
        assertNull(att.contentType)
        val source = att.source
        assertTrue("file() must produce a FileSource", source is PulseAttachment.Source.FileSource)
        assertEquals(file, (source as PulseAttachment.Source.FileSource).file)
    }

    @Test
    fun `file factory allows overriding the server-visible name`() {
        val file = File("/tmp/owl-screens/IMG_0001.HEIC")
        val att = PulseAttachment.file(file, name = "crash-screenshot.heic", contentType = "image/heic")

        assertEquals("crash-screenshot.heic", att.name)
        assertEquals("image/heic", att.contentType)
    }

    @Test
    fun `DataSource has structural equality over its bytes`() {
        val a = PulseAttachment.Source.DataSource("abc".toByteArray())
        val b = PulseAttachment.Source.DataSource("abc".toByteArray())
        val c = PulseAttachment.Source.DataSource("abcd".toByteArray())

        // Distinct ByteArray instances, identical content → equal + same hash.
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertNotEquals(a, c)
        // Identity short-circuit still holds.
        assertSame(a, a)
        assertEquals(a, a)
    }

    @Test
    fun `FileSource is a data class equal by file`() {
        val f = File("/tmp/owl-screens/x.png")
        assertEquals(
            PulseAttachment.Source.FileSource(f),
            PulseAttachment.Source.FileSource(File("/tmp/owl-screens/x.png")),
        )
        assertNotEquals(
            PulseAttachment.Source.FileSource(f),
            PulseAttachment.Source.FileSource(File("/tmp/owl-screens/y.png")),
        )
    }
}
