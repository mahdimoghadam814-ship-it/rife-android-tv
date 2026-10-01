package com.rife.androidtv.rife

import androidx.media3.common.util.UnstableApi
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Bounded output queue implementation using ArrayBlockingQueue.
 * Provides backpressure with configurable drop policies.
 */
@UnstableApi
class BoundedOutputQueue(
    private val capacity: Int = 8
) : OutputQueue {

    private val queue = ArrayBlockingQueue<OutputFrame>(capacity)
    private val completed = AtomicBoolean(false)

    override fun tryEnqueue(frame: OutputFrame): Boolean {
        if (completed.get()) return false
        return queue.offer(frame)
    }

    override fun dequeue(): OutputFrame? {
        return queue.take()
    }

    override fun tryDequeue(): OutputFrame? {
        return queue.poll()
    }

    override fun size(): Int = queue.size

    override fun capacity(): Int = capacity

    override fun clear() {
        queue.clear()
    }

    override fun complete() {
        completed.set(true)
    }

    override fun isCompleted(): Boolean = completed.get() && queue.isEmpty()
}