package pi.ai.core.providers

internal fun sanitizeSurrogates(value: String): String {
    val builder = StringBuilder(value.length)
    var index = 0
    while (index < value.length) {
        val char = value[index]
        when {
            char.isHighSurrogate() && index + 1 < value.length && value[index + 1].isLowSurrogate() -> {
                builder.append(char)
                builder.append(value[index + 1])
                index += 2
            }
            char.isHighSurrogate() || char.isLowSurrogate() -> index++
            else -> {
                builder.append(char)
                index++
            }
        }
    }
    return builder.toString()
}
