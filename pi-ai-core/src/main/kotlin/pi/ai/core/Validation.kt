package pi.ai.core

public fun <TArguments> validateToolArguments(
    tool: Tool<TArguments>,
    toolCall: ToolCall,
): TArguments = tool.validateArguments(toolCall.arguments)

public fun <TArguments> validateToolCall(
    tools: List<Tool<TArguments>>,
    toolCall: ToolCall,
): TArguments {
    val tool: Tool<TArguments> =
        tools.firstOrNull { it.name == toolCall.name }
            ?: throw IllegalArgumentException("Tool \"${toolCall.name}\" not found")
    return validateToolArguments(tool, toolCall)
}
