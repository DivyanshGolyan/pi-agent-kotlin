package pi.coding.agent.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

class MigrationTest {
    @Test
    fun `migrate v1 entries adds id and parent chain`() {
        val entries =
            mutableListOf<FileEntry>(
                SessionHeader(id = "sess-1", timestamp = "2025-01-01T00:00:00Z", cwd = "/tmp"),
                SessionMessageEntry(id = "", parentId = null, timestamp = "2025-01-01T00:00:01Z", message = userMsg("hi")),
                SessionMessageEntry(id = "", parentId = null, timestamp = "2025-01-01T00:00:02Z", message = assistantMsg("hello")),
            )

        migrateSessionEntries(entries)

        val header = entries[0] as SessionHeader
        val msg1 = entries[1] as SessionMessageEntry
        val msg2 = entries[2] as SessionMessageEntry

        assertEquals(3, header.version)
        assertEquals(8, msg1.id.length)
        assertEquals(null, msg1.parentId)
        assertEquals(8, msg2.id.length)
        assertEquals(msg1.id, msg2.parentId)
    }

    @Test
    fun `migrate is idempotent for migrated entries`() {
        val entries =
            mutableListOf<FileEntry>(
                SessionHeader(version = 2, id = "sess-1", timestamp = "2025-01-01T00:00:00Z", cwd = "/tmp"),
                SessionMessageEntry(id = "abc12345", parentId = null, timestamp = "2025-01-01T00:00:01Z", message = userMsg("hi")),
                SessionMessageEntry(
                    id = "def67890",
                    parentId = "abc12345",
                    timestamp = "2025-01-01T00:00:02Z",
                    message = assistantMsg("hello"),
                ),
            )

        migrateSessionEntries(entries)

        val msg1 = entries[1] as SessionMessageEntry
        val msg2 = entries[2] as SessionMessageEntry
        assertEquals("abc12345", msg1.id)
        assertEquals("def67890", msg2.id)
        assertEquals("abc12345", msg2.parentId)
        assertNotNull((entries[0] as SessionHeader).version)
    }
}
