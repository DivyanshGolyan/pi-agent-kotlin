package pi.ai.core

import pi.ai.core.providers.registerBuiltins

private val builtinsRegistered: Lazy<Unit> =
    lazy {
        registerBuiltins()
    }

private fun resolveApiProvider(api: Api): ApiProvider {
    builtinsRegistered.value
    return getApiProvider(api) ?: throw IllegalArgumentException("No API provider registered for api: $api")
}

public fun stream(
    model: Model<*>,
    context: Context,
    options: StreamOptions? = null,
): AssistantMessageEventStream = resolveApiProvider(model.api).stream(model, context, options)

public suspend fun complete(
    model: Model<*>,
    context: Context,
    options: StreamOptions? = null,
): AssistantMessage = stream(model, context, options).result()

public fun streamSimple(
    model: Model<*>,
    context: Context,
    options: SimpleStreamOptions? = null,
): AssistantMessageEventStream = resolveApiProvider(model.api).streamSimple(model, context, options)

public suspend fun completeSimple(
    model: Model<*>,
    context: Context,
    options: SimpleStreamOptions? = null,
): AssistantMessage = streamSimple(model, context, options).result()
