package pi.ai.core

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException

public open class EventStream<T, R>(
    private val isComplete: (T) -> Boolean,
    private val extractResult: (T) -> R,
) {
    private val eventsChannel: Channel<T> = Channel(Channel.UNLIMITED)
    private val closed: AtomicBoolean = AtomicBoolean(false)
    private val finalResult: kotlinx.coroutines.CompletableDeferred<R> = kotlinx.coroutines.CompletableDeferred()

    public fun push(event: T) {
        if (closed.get()) {
            return
        }
        if (!eventsChannel.trySend(event).isSuccess) {
            return
        }
        if (isComplete(event)) {
            closed.set(true)
            if (!finalResult.isCompleted) {
                finalResult.complete(extractResult(event))
            }
            eventsChannel.close()
        }
    }

    public fun end(result: R? = null) {
        if (!closed.compareAndSet(false, true)) {
            return
        }
        if (result != null && !finalResult.isCompleted) {
            finalResult.complete(result)
        }
        if (!finalResult.isCompleted) {
            finalResult.completeExceptionally(CancellationException("Event stream ended without a result"))
        }
        eventsChannel.close()
    }

    public fun asFlow(): Flow<T> = eventsChannel.receiveAsFlow()

    public suspend fun result(): R = finalResult.await()
}

public class AssistantMessageEventStream :
    EventStream<AssistantMessageEvent, AssistantMessage>(
        isComplete = { event -> event is AssistantMessageEvent.Done || event is AssistantMessageEvent.Error },
        extractResult = { event ->
            when (event) {
                is AssistantMessageEvent.Done -> event.message
                is AssistantMessageEvent.Error -> event.error
                else -> throw IllegalArgumentException("Unexpected terminal event: $event")
            }
        },
    )
