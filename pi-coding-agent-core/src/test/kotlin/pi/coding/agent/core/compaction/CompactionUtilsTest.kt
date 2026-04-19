package pi.coding.agent.core.compaction

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pi.ai.core.AssistantMessage
import pi.ai.core.StopReason
import pi.ai.core.TextContent
import pi.ai.core.ToolCall
import pi.ai.core.ToolResultMessage
import pi.ai.core.Usage
import pi.ai.core.UsageCost
import pi.ai.core.UserMessage
import pi.ai.core.UserMessageContent

class CompactionUtilsTest {
    @Test
    fun `extract file ops tracks read write and edit tool calls`() {
        val fileOps = createFileOps()
        val message =
            AssistantMessage(
                content =
                    mutableListOf(
                        ToolCall(id = "1", name = "read", arguments = buildJsonObject { put("path", "a.txt") }),
                        ToolCall(id = "2", name = "write", arguments = buildJsonObject { put("path", "b.txt") }),
                        ToolCall(id = "3", name = "edit", arguments = buildJsonObject { put("path", "c.txt") }),
                    ),
                api = "anthropic-messages",
                provider = "anthropic",
                model = "test",
                usage = Usage(cost = UsageCost()),
                stopReason = StopReason.STOP,
                timestamp = 1L,
            )

        extractFileOpsFromMessage(message, fileOps)

        assertEquals(setOf("a.txt"), fileOps.read)
        assertEquals(setOf("b.txt"), fileOps.written)
        assertEquals(setOf("c.txt"), fileOps.edited)
    }

    @Test
    fun `compute file lists keeps modified files out of read only list`() {
        val fileOps =
            FileOperations(
                read = linkedSetOf("a.txt", "b.txt"),
                written = linkedSetOf("b.txt"),
                edited = linkedSetOf("c.txt"),
            )

        val lists = computeFileLists(fileOps)

        assertEquals(listOf("a.txt"), lists.readFiles)
        assertEquals(listOf("b.txt", "c.txt"), lists.modifiedFiles)
    }

    @Test
    fun `format file operations emits xml blocks only when needed`() {
        val formatted = formatFileOperations(listOf("readme.md"), listOf("app.kt"))
        assertTrue(formatted.contains("<read-files>"))
        assertTrue(formatted.contains("<modified-files>"))
        assertEquals("", formatFileOperations(emptyList(), emptyList()))
    }

    @Test
    fun `serialize conversation truncates long tool results only`() {
        val longContent = "x".repeat(5000)
        val result =
            serializeConversation(
                listOf(
                    ToolResultMessage(
                        toolCallId = "tc1",
                        toolName = "read",
                        content = listOf(TextContent(longContent)),
                        isError = false,
                        timestamp = 1L,
                    ),
                ),
            )

        assertTrue(result.contains("[Tool result]:"))
        assertTrue(result.contains("[... 3000 more characters truncated]"))
        assertFalse(result.contains("x".repeat(3000)))
        assertTrue(result.contains("x".repeat(2000)))
    }

    @Test
    fun `serialize conversation keeps long user and assistant messages intact`() {
        val longText = "y".repeat(5000)
        val result =
            serializeConversation(
                listOf(
                    UserMessage(content = UserMessageContent.Text(longText), timestamp = 1L),
                    AssistantMessage(
                        content = mutableListOf(TextContent(longText)),
                        api = "anthropic-messages",
                        provider = "anthropic",
                        model = "test",
                        usage = Usage(cost = UsageCost()),
                        stopReason = StopReason.STOP,
                        timestamp = 2L,
                    ),
                ),
            )

        assertFalse(result.contains("truncated"))
        assertTrue(result.contains(longText))
    }
}
