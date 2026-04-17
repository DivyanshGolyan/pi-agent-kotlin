package pi.ai.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject

private val permissiveJson: Json =
    Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

public fun parseStreamingJson(partial: String): JsonObject {
    if (partial.isBlank()) {
        return buildJsonObject {}
    }

    val candidates: List<String> =
        buildList {
            add(partial)
            add(closeIncompleteJson(partial))
            add(stripDanglingComma(closeIncompleteJson(partial)))
        }

    for (candidate in candidates) {
        runCatching { permissiveJson.parseToJsonElement(candidate) }
            .getOrNull()
            ?.let { parsed ->
                if (parsed is JsonObject) {
                    return parsed
                }
            }
    }

    return buildJsonObject {}
}

private fun closeIncompleteJson(input: String): String {
    val builder: StringBuilder = StringBuilder(input)
    if (hasUnclosedString(input)) {
        builder.append('"')
    }

    val stack: ArrayDeque<Char> = ArrayDeque()
    var inString: Boolean = false
    var escaping: Boolean = false
    input.forEach { ch ->
        if (escaping) {
            escaping = false
            return@forEach
        }
        if (ch == '\\') {
            escaping = true
            return@forEach
        }
        if (ch == '"') {
            inString = !inString
            return@forEach
        }
        if (inString) {
            return@forEach
        }
        when (ch) {
            '{' -> stack.addLast('}')
            '[' -> stack.addLast(']')
            '}', ']' ->
                if (stack.isNotEmpty()) {
                    stack.removeLast()
                }
        }
    }

    while (stack.isNotEmpty()) {
        builder.append(stack.removeLast())
    }
    return builder.toString()
}

private fun hasUnclosedString(input: String): Boolean {
    var escaping: Boolean = false
    var inString: Boolean = false
    input.forEach { ch ->
        if (escaping) {
            escaping = false
            return@forEach
        }
        if (ch == '\\') {
            escaping = true
            return@forEach
        }
        if (ch == '"') {
            inString = !inString
        }
    }
    return inString
}

private fun stripDanglingComma(input: String): String = input.replace(Regex(",\\s*([}\\]])"), "$1")
