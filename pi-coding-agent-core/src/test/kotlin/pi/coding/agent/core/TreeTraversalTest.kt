package pi.coding.agent.core

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.io.path.createTempDirectory

class TreeTraversalTest {
    @Test
    fun `append operations build parent chain`() {
        val session = SessionManager.inMemory()

        val id1 = session.appendMessage(userMsg("first"))
        val id2 = session.appendMessage(assistantMsg("second"))
        val id3 = session.appendThinkingLevelChange("high")
        val id4 = session.appendMessage(userMsg("third"))

        val entries = session.getEntries()
        assertEquals(listOf(id1, id2, id3, id4), entries.map { it.id })
        assertEquals(null, entries[0].parentId)
        assertEquals(id1, entries[1].parentId)
        assertEquals(id2, entries[2].parentId)
        assertEquals(id3, entries[3].parentId)
        assertEquals(id4, session.getLeafId())
    }

    @Test
    fun `get branch returns path from root to leaf`() {
        val session = SessionManager.inMemory()

        val id1 = session.appendMessage(userMsg("1"))
        val id2 = session.appendMessage(assistantMsg("2"))
        session.appendMessage(userMsg("3"))
        session.appendMessage(assistantMsg("4"))

        assertEquals(listOf(id1, id2), session.getBranch(id2).map { it.id })
        assertEquals(4, session.getBranch().size)
    }

    @Test
    fun `get tree exposes sibling branches`() {
        val session = SessionManager.inMemory()

        val id1 = session.appendMessage(userMsg("1"))
        val id2 = session.appendMessage(assistantMsg("2"))
        val id3 = session.appendMessage(userMsg("3"))

        session.branch(id2)
        val id4 = session.appendMessage(userMsg("4-branch"))

        val tree = session.getTree()
        assertEquals(1, tree.size)
        assertEquals(id1, tree[0].entry.id)
        assertEquals(id2, tree[0].children[0].entry.id)
        val childIds =
            tree[0]
                .children[0]
                .children
                .map { it.entry.id }
                .sorted()
        assertEquals(listOf(id3, id4).sorted(), childIds)
    }

    @Test
    fun `branch and branch with summary move leaf pointer`() {
        val session = SessionManager.inMemory()

        val id1 = session.appendMessage(userMsg("1"))
        session.appendMessage(assistantMsg("2"))
        session.appendMessage(userMsg("3"))

        session.branch(id1)
        assertEquals(id1, session.getLeafId())

        val summaryId = session.branchWithSummary(id1, "Summary of abandoned work")
        assertEquals(summaryId, session.getLeafId())

        val summaryEntry = session.getEntries().firstOrNull { it is BranchSummaryEntry } as BranchSummaryEntry
        assertEquals(id1, summaryEntry.parentId)
        assertEquals("Summary of abandoned work", summaryEntry.summary)
    }

    @Test
    fun `branch throws for unknown entry`() {
        val session = SessionManager.inMemory()
        session.appendMessage(userMsg("hello"))

        val error = assertThrows(IllegalArgumentException::class.java) { session.branch("missing") }
        assertEquals("Entry missing not found", error.message)
    }

    @Test
    fun `build session context follows current branch only`() {
        val session = SessionManager.inMemory()

        session.appendMessage(userMsg("msg1"))
        val id2 = session.appendMessage(assistantMsg("msg2"))
        session.appendMessage(userMsg("msg3"))

        session.branch(id2)
        session.appendMessage(assistantMsg("msg4-branch"))

        val context = session.buildSessionContext()
        assertEquals(3, context.messages.size)
        assertEquals(
            "msg1",
            (context.messages[0] as pi.ai.core.UserMessage).let { (it.content as pi.ai.core.UserMessageContent.Text).value },
        )
        assertEquals("msg2", ((context.messages[1] as pi.ai.core.AssistantMessage).content[0] as pi.ai.core.TextContent).text)
        assertEquals("msg4-branch", ((context.messages[2] as pi.ai.core.AssistantMessage).content[0] as pi.ai.core.TextContent).text)
    }

    @Test
    fun `create branched session trims in-memory history to selected path`() {
        val session = SessionManager.inMemory()

        val id1 = session.appendMessage(userMsg("1"))
        val id2 = session.appendMessage(assistantMsg("2"))
        val id3 = session.appendMessage(userMsg("3"))
        session.appendMessage(assistantMsg("4"))

        session.branch(id3)
        session.appendMessage(userMsg("5"))

        val result = session.createBranchedSession(id2)
        assertEquals(null, result)
        assertEquals(listOf(id1, id2), session.getEntries().map { it.id })
    }

    @Test
    fun `forking from first user message delays file until assistant arrives`() {
        val tempDir = createTempDirectory("session-fork-dedup-")

        try {
            val session = SessionManager.create(tempDir.toString(), tempDir.toString())
            val id1 = session.appendMessage(userMsg("first question"))
            session.appendMessage(assistantMsg("first answer"))
            session.appendMessage(userMsg("second question"))
            session.appendMessage(assistantMsg("second answer"))

            val newFile = session.createBranchedSession(id1)
            assertNotNull(newFile)
            assertFalse(
                Files.exists(
                    java.nio.file.Paths
                        .get(newFile!!),
                ),
            )

            session.appendCustomEntry("preset-state", buildJsonObject { put("name", "plan") })
            session.appendMessage(assistantMsg("new answer"))

            val path =
                java.nio.file.Paths
                    .get(newFile)
            assertTrue(Files.exists(path))

            val entries = loadEntriesFromFile(newFile)
            assertEquals(1, entries.count { it is SessionHeader })
            val ids = entries.filterIsInstance<SessionEntry>().map { it.id }
            assertEquals(ids.size, ids.toSet().size)
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `forking from assistant path writes file immediately`() {
        val tempDir = createTempDirectory("session-fork-with-assistant-")

        try {
            val session = SessionManager.create(tempDir.toString(), tempDir.toString())
            session.appendMessage(userMsg("first question"))
            val id2 = session.appendMessage(assistantMsg("first answer"))
            session.appendMessage(userMsg("second question"))
            session.appendMessage(assistantMsg("second answer"))

            val newFile = session.createBranchedSession(id2)
            assertNotNull(newFile)
            assertTrue(
                Files.exists(
                    java.nio.file.Paths
                        .get(newFile!!),
                ),
            )
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }
}
