package pi.coding.agent.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import pi.ai.core.AssistantContentBlock
import pi.ai.core.AssistantMessage
import pi.ai.core.ImageContent
import pi.ai.core.Message
import pi.ai.core.StopReason
import pi.ai.core.TextContent
import pi.ai.core.ThinkingContent
import pi.ai.core.ToolCall
import pi.ai.core.ToolResultContentPart
import pi.ai.core.ToolResultMessage
import pi.ai.core.Usage
import pi.ai.core.UsageCost
import pi.ai.core.UserContentPart
import pi.ai.core.UserMessage
import pi.ai.core.UserMessageContent

internal val sessionJson: Json =
    Json {
        ignoreUnknownKeys = true
        prettyPrint = false
    }

internal fun parseFileEntry(line: String): FileEntry? =
    runCatching {
        parseFileEntry(sessionJson.parseToJsonElement(line).jsonObject)
    }.getOrNull()

internal fun parseFileEntry(json: JsonObject): FileEntry? =
    when (json.string("type")) {
        "session" -> parseSessionHeader(json)
        "message" -> parseSessionMessageEntry(json)
        "thinking_level_change" -> parseThinkingLevelChangeEntry(json)
        "model_change" -> parseModelChangeEntry(json)
        "compaction" -> parseCompactionEntry(json)
        "branch_summary" -> parseBranchSummaryEntry(json)
        "custom" -> parseCustomEntry(json)
        "custom_message" -> parseCustomMessageEntry(json)
        "label" -> parseLabelEntry(json)
        "session_info" -> parseSessionInfoEntry(json)
        else -> null
    }

internal fun fileEntryToJson(entry: FileEntry): JsonObject =
    when (entry) {
        is SessionHeader ->
            buildJsonObject {
                put("type", entry.type)
                entry.version?.let { put("version", it) }
                put("id", entry.id)
                put("timestamp", entry.timestamp)
                put("cwd", entry.cwd)
                entry.parentSession?.let { put("parentSession", it) }
            }
        is SessionMessageEntry ->
            buildJsonObject {
                put("type", entry.type)
                put("id", entry.id)
                put("parentId", entry.parentId)
                put("timestamp", entry.timestamp)
                put("message", messageToJson(entry.message))
            }
        is ThinkingLevelChangeEntry ->
            buildJsonObject {
                put("type", entry.type)
                put("id", entry.id)
                put("parentId", entry.parentId)
                put("timestamp", entry.timestamp)
                put("thinkingLevel", entry.thinkingLevel)
            }
        is ModelChangeEntry ->
            buildJsonObject {
                put("type", entry.type)
                put("id", entry.id)
                put("parentId", entry.parentId)
                put("timestamp", entry.timestamp)
                put("provider", entry.provider)
                put("modelId", entry.modelId)
            }
        is CompactionEntry ->
            buildJsonObject {
                put("type", entry.type)
                put("id", entry.id)
                put("parentId", entry.parentId)
                put("timestamp", entry.timestamp)
                put("summary", entry.summary)
                put("firstKeptEntryId", entry.firstKeptEntryId)
                put("tokensBefore", entry.tokensBefore)
                entry.details?.let { put("details", it) }
                entry.fromHook?.let { put("fromHook", it) }
            }
        is BranchSummaryEntry ->
            buildJsonObject {
                put("type", entry.type)
                put("id", entry.id)
                put("parentId", entry.parentId)
                put("timestamp", entry.timestamp)
                put("fromId", entry.fromId)
                put("summary", entry.summary)
                entry.details?.let { put("details", it) }
                entry.fromHook?.let { put("fromHook", it) }
            }
        is CustomEntry ->
            buildJsonObject {
                put("type", entry.type)
                put("id", entry.id)
                put("parentId", entry.parentId)
                put("timestamp", entry.timestamp)
                put("customType", entry.customType)
                entry.data?.let { put("data", it) }
            }
        is CustomMessageEntry ->
            buildJsonObject {
                put("type", entry.type)
                put("id", entry.id)
                put("parentId", entry.parentId)
                put("timestamp", entry.timestamp)
                put("customType", entry.customType)
                put("content", userMessageContentToJson(entry.content))
                put("display", entry.display)
                entry.details?.let { put("details", it) }
            }
        is LabelEntry ->
            buildJsonObject {
                put("type", entry.type)
                put("id", entry.id)
                put("parentId", entry.parentId)
                put("timestamp", entry.timestamp)
                put("targetId", entry.targetId)
                put("label", entry.label)
            }
        is SessionInfoEntry ->
            buildJsonObject {
                put("type", entry.type)
                put("id", entry.id)
                put("parentId", entry.parentId)
                put("timestamp", entry.timestamp)
                put("name", entry.name)
            }
    }

internal fun fileEntryToLine(entry: FileEntry): String = sessionJson.encodeToString(JsonObject.serializer(), fileEntryToJson(entry))

private fun parseSessionHeader(json: JsonObject): SessionHeader =
    SessionHeader(
        version = json.intOrNull("version"),
        id = json.string("id").orEmpty(),
        timestamp = json.string("timestamp").orEmpty(),
        cwd = json.string("cwd").orEmpty(),
        parentSession = json.string("parentSession"),
    )

private fun parseSessionMessageEntry(json: JsonObject): SessionMessageEntry? {
    val message = json.obj("message")?.let(::parseMessage) ?: return null
    return SessionMessageEntry(
        id = json.string("id").orEmpty(),
        parentId = json.string("parentId"),
        timestamp = json.string("timestamp").orEmpty(),
        message = message,
    )
}

private fun parseThinkingLevelChangeEntry(json: JsonObject): ThinkingLevelChangeEntry =
    ThinkingLevelChangeEntry(
        id = json.string("id").orEmpty(),
        parentId = json.string("parentId"),
        timestamp = json.string("timestamp").orEmpty(),
        thinkingLevel = json.string("thinkingLevel").orEmpty(),
    )

private fun parseModelChangeEntry(json: JsonObject): ModelChangeEntry =
    ModelChangeEntry(
        id = json.string("id").orEmpty(),
        parentId = json.string("parentId"),
        timestamp = json.string("timestamp").orEmpty(),
        provider = json.string("provider").orEmpty(),
        modelId = json.string("modelId").orEmpty(),
    )

private fun parseCompactionEntry(json: JsonObject): CompactionEntry =
    CompactionEntry(
        id = json.string("id").orEmpty(),
        parentId = json.string("parentId"),
        timestamp = json.string("timestamp").orEmpty(),
        summary = json.string("summary").orEmpty(),
        firstKeptEntryId = json.string("firstKeptEntryId").orEmpty(),
        tokensBefore = json.intOrNull("tokensBefore") ?: 0,
        details = json["details"],
        fromHook = json.booleanOrNull("fromHook"),
    )

private fun parseBranchSummaryEntry(json: JsonObject): BranchSummaryEntry =
    BranchSummaryEntry(
        id = json.string("id").orEmpty(),
        parentId = json.string("parentId"),
        timestamp = json.string("timestamp").orEmpty(),
        fromId = json.string("fromId").orEmpty(),
        summary = json.string("summary").orEmpty(),
        details = json["details"],
        fromHook = json.booleanOrNull("fromHook"),
    )

private fun parseCustomEntry(json: JsonObject): CustomEntry =
    CustomEntry(
        id = json.string("id").orEmpty(),
        parentId = json.string("parentId"),
        timestamp = json.string("timestamp").orEmpty(),
        customType = json.string("customType").orEmpty(),
        data = json["data"],
    )

private fun parseCustomMessageEntry(json: JsonObject): CustomMessageEntry =
    CustomMessageEntry(
        id = json.string("id").orEmpty(),
        parentId = json.string("parentId"),
        timestamp = json.string("timestamp").orEmpty(),
        customType = json.string("customType").orEmpty(),
        content = parseUserMessageContent(json["content"]),
        details = json["details"],
        display = json.booleanOrNull("display") ?: false,
    )

private fun parseLabelEntry(json: JsonObject): LabelEntry =
    LabelEntry(
        id = json.string("id").orEmpty(),
        parentId = json.string("parentId"),
        timestamp = json.string("timestamp").orEmpty(),
        targetId = json.string("targetId").orEmpty(),
        label = json.string("label"),
    )

private fun parseSessionInfoEntry(json: JsonObject): SessionInfoEntry =
    SessionInfoEntry(
        id = json.string("id").orEmpty(),
        parentId = json.string("parentId"),
        timestamp = json.string("timestamp").orEmpty(),
        name = json.string("name"),
    )

internal fun parseMessage(json: JsonObject): Message? =
    when (json.string("role")) {
        "user" -> parseUserMessage(json)
        "assistant" -> parseAssistantMessage(json)
        "toolResult" -> parseToolResultMessage(json)
        "bashExecution" -> parseBashExecutionMessage(json)
        "custom", "hookMessage" -> parseCustomMessage(json)
        "branchSummary" -> parseBranchSummaryMessage(json)
        "compactionSummary" -> parseCompactionSummaryMessage(json)
        else -> null
    }

internal fun messageToJson(message: Message): JsonObject =
    when (message) {
        is UserMessage ->
            buildJsonObject {
                put("role", message.role)
                put("content", userMessageContentToJson(message.content))
                put("timestamp", message.timestamp)
            }
        is AssistantMessage ->
            buildJsonObject {
                put("role", message.role)
                put("content", assistantContentToJson(message.content))
                put("api", message.api)
                put("provider", message.provider)
                put("model", message.model)
                put("usage", usageToJson(message.usage))
                put("stopReason", stopReasonToJson(message.stopReason))
                put("timestamp", message.timestamp)
                message.errorMessage?.let { put("errorMessage", it) }
                message.responseId?.let { put("responseId", it) }
            }
        is ToolResultMessage ->
            buildJsonObject {
                put("role", message.role)
                put("toolCallId", message.toolCallId)
                put("toolName", message.toolName)
                put("content", toolResultContentToJson(message.content))
                message.details?.let { put("details", it) }
                put("isError", message.isError)
                put("timestamp", message.timestamp)
            }
        is BashExecutionMessage ->
            buildJsonObject {
                put("role", message.role)
                put("command", message.command)
                put("output", message.output)
                if (message.exitCode == null) {
                    put("exitCode", JsonNull)
                } else {
                    put("exitCode", message.exitCode)
                }
                put("cancelled", message.cancelled)
                put("truncated", message.truncated)
                message.fullOutputPath?.let { put("fullOutputPath", it) }
                put("timestamp", message.timestamp)
                if (message.excludeFromContext) {
                    put("excludeFromContext", true)
                }
            }
        is CustomMessage ->
            buildJsonObject {
                put("role", message.role)
                put("customType", message.customType)
                put("content", userMessageContentToJson(message.content))
                put("display", message.display)
                message.details?.let { put("details", it) }
                put("timestamp", message.timestamp)
            }
        is BranchSummaryMessage ->
            buildJsonObject {
                put("role", message.role)
                put("summary", message.summary)
                put("fromId", message.fromId)
                put("timestamp", message.timestamp)
            }
        is CompactionSummaryMessage ->
            buildJsonObject {
                put("role", message.role)
                put("summary", message.summary)
                put("tokensBefore", message.tokensBefore)
                put("timestamp", message.timestamp)
            }
        else -> error("Unsupported message type: ${message::class.qualifiedName}")
    }

private fun parseUserMessage(json: JsonObject): UserMessage =
    UserMessage(
        content = parseUserMessageContent(json["content"]),
        timestamp = json.longOrNull("timestamp") ?: 0L,
    )

private fun parseAssistantMessage(json: JsonObject): AssistantMessage {
    val content = parseAssistantContent(json["content"])
    return AssistantMessage(
        content = content.toMutableList(),
        api = json.string("api").orEmpty(),
        provider = json.string("provider").orEmpty(),
        model = json.string("model").orEmpty(),
        usage = parseUsage(json.obj("usage")),
        stopReason = parseStopReason(json.string("stopReason")),
        errorMessage = json.string("errorMessage"),
        timestamp = json.longOrNull("timestamp") ?: 0L,
        responseId = json.string("responseId"),
    )
}

private fun parseToolResultMessage(json: JsonObject): ToolResultMessage =
    ToolResultMessage(
        toolCallId = json.string("toolCallId").orEmpty(),
        toolName = json.string("toolName").orEmpty(),
        content = parseToolResultContent(json["content"]),
        details = json["details"],
        isError = json.booleanOrNull("isError") ?: false,
        timestamp = json.longOrNull("timestamp") ?: 0L,
    )

private fun parseBashExecutionMessage(json: JsonObject): BashExecutionMessage =
    BashExecutionMessage(
        command = json.string("command").orEmpty(),
        output = json.string("output").orEmpty(),
        exitCode = json.intOrNull("exitCode"),
        cancelled = json.booleanOrNull("cancelled") ?: false,
        truncated = json.booleanOrNull("truncated") ?: false,
        fullOutputPath = json.string("fullOutputPath"),
        timestamp = json.longOrNull("timestamp") ?: 0L,
        excludeFromContext = json.booleanOrNull("excludeFromContext") ?: false,
    )

private fun parseCustomMessage(json: JsonObject): CustomMessage =
    CustomMessage(
        customType = json.string("customType").orEmpty(),
        content = parseUserMessageContent(json["content"]),
        display = json.booleanOrNull("display") ?: false,
        details = json["details"],
        timestamp = json.longOrNull("timestamp") ?: 0L,
    )

private fun parseBranchSummaryMessage(json: JsonObject): BranchSummaryMessage =
    BranchSummaryMessage(
        summary = json.string("summary").orEmpty(),
        fromId = json.string("fromId").orEmpty(),
        timestamp = json.longOrNull("timestamp") ?: 0L,
    )

private fun parseCompactionSummaryMessage(json: JsonObject): CompactionSummaryMessage =
    CompactionSummaryMessage(
        summary = json.string("summary").orEmpty(),
        tokensBefore = json.intOrNull("tokensBefore") ?: 0,
        timestamp = json.longOrNull("timestamp") ?: 0L,
    )

internal fun parseUserMessageContent(element: JsonElement?): UserMessageContent {
    if (element == null || element is JsonNull) {
        return UserMessageContent.Text("")
    }

    return when (element) {
        is JsonPrimitive -> UserMessageContent.Text(element.contentOrNull.orEmpty())
        is JsonArray -> UserMessageContent.Structured(element.map(::parseUserContentPart))
        else -> UserMessageContent.Text(element.toString())
    }
}

internal fun userMessageContentToJson(content: UserMessageContent): JsonElement =
    when (content) {
        is UserMessageContent.Text -> JsonPrimitive(content.value)
        is UserMessageContent.Structured -> buildJsonArray { content.parts.forEach { add(userContentPartToJson(it)) } }
    }

private fun parseUserContentPart(element: JsonElement): UserContentPart {
    val obj = element.jsonObject
    return when (obj.string("type")) {
        "image" -> ImageContent(data = obj.string("data").orEmpty(), mimeType = obj.string("mimeType").orEmpty())
        else -> TextContent(text = obj.string("text").orEmpty(), textSignature = obj.string("textSignature"))
    }
}

private fun userContentPartToJson(part: UserContentPart): JsonObject =
    when (part) {
        is TextContent ->
            buildJsonObject {
                put("type", "text")
                put("text", part.text)
                part.textSignature?.let { put("textSignature", it) }
            }
        is ImageContent ->
            buildJsonObject {
                put("type", "image")
                put("data", part.data)
                put("mimeType", part.mimeType)
            }
    }

private fun parseAssistantContent(element: JsonElement?): List<AssistantContentBlock> {
    if (element == null || element is JsonNull) {
        return emptyList()
    }
    if (element is JsonPrimitive) {
        return listOf(TextContent(element.contentOrNull.orEmpty()))
    }

    return element.jsonArray.map { item ->
        val obj = item.jsonObject
        when (obj.string("type")) {
            "thinking" ->
                ThinkingContent(
                    thinking = obj.string("thinking").orEmpty(),
                    thinkingSignature = obj.string("thinkingSignature"),
                    redacted = obj.booleanOrNull("redacted") ?: false,
                )
            "toolCall", "tool_call" ->
                ToolCall(
                    id = obj.string("id").orEmpty(),
                    name = obj.string("name").orEmpty(),
                    arguments = obj.obj("arguments") ?: JsonObject(emptyMap()),
                    thoughtSignature = obj.string("thoughtSignature"),
                )
            else -> TextContent(text = obj.string("text").orEmpty(), textSignature = obj.string("textSignature"))
        }
    }
}

private fun assistantContentToJson(content: List<AssistantContentBlock>): JsonArray =
    buildJsonArray {
        content.forEach { block ->
            add(
                when (block) {
                    is TextContent ->
                        buildJsonObject {
                            put("type", "text")
                            put("text", block.text)
                            block.textSignature?.let { put("textSignature", it) }
                        }
                    is ThinkingContent ->
                        buildJsonObject {
                            put("type", "thinking")
                            put("thinking", block.thinking)
                            block.thinkingSignature?.let { put("thinkingSignature", it) }
                            if (block.redacted) {
                                put("redacted", true)
                            }
                        }
                    is ToolCall ->
                        buildJsonObject {
                            put("type", "toolCall")
                            put("id", block.id)
                            put("name", block.name)
                            put("arguments", block.arguments)
                            block.thoughtSignature?.let { put("thoughtSignature", it) }
                        }
                    else -> error("Unsupported assistant content block: ${block::class.qualifiedName}")
                },
            )
        }
    }

private fun parseToolResultContent(element: JsonElement?): List<ToolResultContentPart> {
    if (element == null || element is JsonNull) {
        return emptyList()
    }

    return element.jsonArray.map { item ->
        val obj = item.jsonObject
        when (obj.string("type")) {
            "image" -> ImageContent(data = obj.string("data").orEmpty(), mimeType = obj.string("mimeType").orEmpty())
            else -> TextContent(text = obj.string("text").orEmpty(), textSignature = obj.string("textSignature"))
        }
    }
}

private fun toolResultContentToJson(content: List<ToolResultContentPart>): JsonArray =
    buildJsonArray {
        content.forEach { block ->
            add(
                when (block) {
                    is TextContent ->
                        buildJsonObject {
                            put("type", "text")
                            put("text", block.text)
                            block.textSignature?.let { put("textSignature", it) }
                        }
                    is ImageContent ->
                        buildJsonObject {
                            put("type", "image")
                            put("data", block.data)
                            put("mimeType", block.mimeType)
                        }
                },
            )
        }
    }

private fun parseUsage(obj: JsonObject?): Usage {
    val costObj = obj?.obj("cost")
    return Usage(
        input = obj?.intOrNull("input") ?: 0,
        output = obj?.intOrNull("output") ?: 0,
        cacheRead = obj?.intOrNull("cacheRead") ?: 0,
        cacheWrite = obj?.intOrNull("cacheWrite") ?: 0,
        totalTokens = obj?.intOrNull("totalTokens") ?: 0,
        cost =
            UsageCost(
                input = costObj?.doubleOrNull("input") ?: 0.0,
                output = costObj?.doubleOrNull("output") ?: 0.0,
                cacheRead = costObj?.doubleOrNull("cacheRead") ?: 0.0,
                cacheWrite = costObj?.doubleOrNull("cacheWrite") ?: 0.0,
                total = costObj?.doubleOrNull("total") ?: 0.0,
            ),
    )
}

private fun usageToJson(usage: Usage): JsonObject =
    buildJsonObject {
        put("input", usage.input)
        put("output", usage.output)
        put("cacheRead", usage.cacheRead)
        put("cacheWrite", usage.cacheWrite)
        put("totalTokens", usage.totalTokens)
        put(
            "cost",
            buildJsonObject {
                put("input", usage.cost.input)
                put("output", usage.cost.output)
                put("cacheRead", usage.cost.cacheRead)
                put("cacheWrite", usage.cost.cacheWrite)
                put("total", usage.cost.total)
            },
        )
    }

private fun parseStopReason(value: String?): StopReason =
    when (value) {
        "length" -> StopReason.LENGTH
        "toolUse" -> StopReason.TOOL_USE
        "error" -> StopReason.ERROR
        "aborted" -> StopReason.ABORTED
        else -> StopReason.STOP
    }

private fun stopReasonToJson(value: StopReason): String =
    when (value) {
        StopReason.STOP -> "stop"
        StopReason.LENGTH -> "length"
        StopReason.TOOL_USE -> "toolUse"
        StopReason.ERROR -> "error"
        StopReason.ABORTED -> "aborted"
    }

private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

private fun JsonObject.intOrNull(key: String): Int? = this[key]?.jsonPrimitive?.intOrNull

private fun JsonObject.longOrNull(key: String): Long? = this[key]?.jsonPrimitive?.longOrNull

private fun JsonObject.booleanOrNull(key: String): Boolean? = this[key]?.jsonPrimitive?.booleanOrNull

private fun JsonObject.doubleOrNull(key: String): Double? = this[key]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()

private fun JsonObject.obj(key: String): JsonObject? = this[key]?.jsonObject
