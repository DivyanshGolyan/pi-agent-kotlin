package pi.ai.core

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

public class AbortSignal internal constructor() {
    private val abortedState: AtomicBoolean = AtomicBoolean(false)
    private val listeners: CopyOnWriteArrayList<() -> Unit> = CopyOnWriteArrayList()

    public val aborted: Boolean
        get() = abortedState.get()

    public fun addListener(listener: () -> Unit): () -> Unit {
        if (aborted) {
            listener()
            return {}
        }
        listeners += listener
        if (abortedState.get()) {
            listeners -= listener
            listener()
            return {}
        }
        return { listeners -= listener }
    }

    internal fun abort() {
        if (!abortedState.compareAndSet(false, true)) {
            return
        }
        listeners.forEach { it() }
        listeners.clear()
    }
}

public class AbortController {
    public val signal: AbortSignal = AbortSignal()

    public fun abort(): Unit = signal.abort()
}
