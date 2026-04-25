package pi.ai.core.providers

import pi.ai.core.registerApiProvider

private var registered: Boolean = false

public fun registerBuiltins() {
    if (registered) {
        return
    }
    registerApiProvider(AnthropicApiProvider)
    registerApiProvider(GoogleApiProvider)
    registered = true
}
