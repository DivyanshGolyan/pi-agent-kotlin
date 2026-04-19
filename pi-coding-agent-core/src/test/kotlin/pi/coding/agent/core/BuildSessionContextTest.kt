package pi.coding.agent.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import pi.agent.core.AgentMessage
import pi.ai.core.AssistantMessage
import pi.ai.core.UserMessage

class BuildSessionContextTest {
    @Test
    fun `empty entries returns empty context`() {
        val context = buildSessionContext(emptyList())

        assertEquals(emptyList<AgentMessage>(), context.messages)
        assertEquals("off", context.thinkingLevel)
        assertNull(context.model)
    }

    @Test
    fun `simple conversation follows linear order`() {
        val entries =
            listOf<SessionEntry>(
                messageEntry("1", null, userMsg("hello")),
                messageEntry("2", "1", assistantMsg("hi there")),
                messageEntry("3", "2", userMsg("how are you")),
                messageEntry("4", "3", assistantMsg("great")),
            )

        val context = buildSessionContext(entries)

        assertEquals(listOf("user", "assistant", "user", "assistant"), context.messages.map { it.role })
        assertEquals("great", assistantText(context.messages[3]))
    }

    @Test
    fun `tracks thinking level and model along path`() {
        val entries =
            listOf<SessionEntry>(
                messageEntry("1", null, userMsg("hello")),
                ThinkingLevelChangeEntry(id = "2", parentId = "1", timestamp = "2025-01-01T00:00:00Z", thinkingLevel = "high"),
                ModelChangeEntry(id = "3", parentId = "2", timestamp = "2025-01-01T00:00:00Z", provider = "openai", modelId = "gpt-4"),
                messageEntry("4", "3", assistantMsg("thinking hard")),
            )

        val context = buildSessionContext(entries)

        assertEquals("high", context.thinkingLevel)
        assertEquals(SessionModel(provider = "anthropic", modelId = "test"), context.model)
    }

    @Test
    fun `compaction emits summary then kept history`() {
        val entries =
            listOf<SessionEntry>(
                messageEntry("1", null, userMsg("first")),
                messageEntry("2", "1", assistantMsg("response1")),
                messageEntry("3", "2", userMsg("second")),
                messageEntry("4", "3", assistantMsg("response2")),
                CompactionEntry(
                    id = "5",
                    parentId = "4",
                    timestamp = "2025-01-01T00:00:00Z",
                    summary = "Summary of first two turns",
                    firstKeptEntryId = "3",
                    tokensBefore = 1000,
                ),
                messageEntry("6", "5", userMsg("third")),
                messageEntry("7", "6", assistantMsg("response3")),
            )

        val context = buildSessionContext(entries)

        assertEquals(5, context.messages.size)
        val summary = context.messages[0] as CompactionSummaryMessage
        assertEquals("Summary of first two turns", summary.summary)
        assertEquals("second", userText(context.messages[1]))
        assertEquals("response2", assistantText(context.messages[2]))
        assertEquals("third", userText(context.messages[3]))
        assertEquals("response3", assistantText(context.messages[4]))
    }

    @Test
    fun `branch traversal follows selected leaf`() {
        val entries =
            listOf<SessionEntry>(
                messageEntry("1", null, userMsg("start")),
                messageEntry("2", "1", assistantMsg("response")),
                messageEntry("3", "2", userMsg("branch A")),
                messageEntry("4", "2", userMsg("branch B")),
            )

        val branchA = buildSessionContext(entries, "3")
        val branchB = buildSessionContext(entries, "4")

        assertEquals("branch A", userText(branchA.messages.last()))
        assertEquals("branch B", userText(branchB.messages.last()))
    }

    @Test
    fun `branch summary is part of rebuilt context`() {
        val entries =
            listOf<SessionEntry>(
                messageEntry("1", null, userMsg("start")),
                messageEntry("2", "1", assistantMsg("response")),
                messageEntry("3", "2", userMsg("abandoned path")),
                BranchSummaryEntry(
                    id = "4",
                    parentId = "2",
                    timestamp = "2025-01-01T00:00:00Z",
                    fromId = "3",
                    summary = "Summary of abandoned work",
                ),
                messageEntry("5", "4", userMsg("new direction")),
            )

        val context = buildSessionContext(entries, "5")

        assertEquals(4, context.messages.size)
        val summary = context.messages[2] as BranchSummaryMessage
        assertEquals("Summary of abandoned work", summary.summary)
        assertEquals("new direction", userText(context.messages[3]))
    }

    private fun messageEntry(
        id: String,
        parentId: String?,
        message: pi.ai.core.Message,
    ): SessionMessageEntry = SessionMessageEntry(id = id, parentId = parentId, timestamp = "2025-01-01T00:00:00Z", message = message)

    private fun userText(message: AgentMessage): String? =
        (message as? UserMessage)?.let { content ->
            (content.content as? pi.ai.core.UserMessageContent.Text)?.value
        }

    private fun assistantText(message: AgentMessage): String? =
        (message as? AssistantMessage)
            ?.content
            ?.filterIsInstance<pi.ai.core.TextContent>()
            ?.joinToString(" ") { it.text }
}
