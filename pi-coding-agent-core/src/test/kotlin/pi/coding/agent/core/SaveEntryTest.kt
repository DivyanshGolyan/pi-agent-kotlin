package pi.coding.agent.core

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

class SaveEntryTest {
    @Test
    fun `custom entries are saved in tree but skipped from llm context`() {
        val session = SessionManager.inMemory()

        val msgId = session.appendMessage(userMsg("hello"))
        val customId = session.appendCustomEntry("my_data", buildJsonObject { put("foo", "bar") })
        val msg2Id = session.appendMessage(assistantMsg("hi"))

        val entries = session.getEntries()
        assertEquals(3, entries.size)

        val customEntry = entries.firstOrNull { it is CustomEntry } as CustomEntry
        assertNotNull(customEntry)
        assertEquals("my_data", customEntry.customType)
        assertEquals(customId, customEntry.id)
        assertEquals(msgId, customEntry.parentId)

        val branch = session.getBranch()
        assertEquals(listOf(msgId, customId, msg2Id), branch.map { it.id })

        val context = session.buildSessionContext()
        assertEquals(2, context.messages.size)
    }
}
