/* **************************************************************************
 * Copyright (c) 2019 VMware, Inc.  All rights reserved. VMware Confidential
 * *************************************************************************/
package com.vmware.blockchain.deployment.reactive

import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.BroadcastChannel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.launch
import kotlinx.coroutines.reactive.publish
import kotlinx.coroutines.withContext

/**
 * An implementation of [Publisher] of element of type [T] that publishes to downstream subscribers
 * in a broadcasting manner.
 *
 * @param[T]
 *   type of element to be published.
 * @param[capacity]
 *   buffer capacity of the publisher to hold outstanding unconsumed broadcast elements.
 * @param[context]
 *   parent [coroutineContext] to base this instance's internal context on.
 */
class BroadcastingPublisher<T>(
    private val capacity: Int,
    private val context: CoroutineContext = Dispatchers.Default
) : Publisher<T>, CoroutineScope {

    /** [CoroutineContext] to launch all coroutines associated with this instance. */
    override val coroutineContext: CoroutineContext
        get() = context + job

    /** [CoroutineContext] for all coroutines launched for sending to downstream subscribers. */
    private val publishContext: CoroutineContext by lazy { coroutineContext }

    /** Parent [Job] of all coroutines associated with this instance's operation. */
    private val job: Job = Job()

    /** Internal buffered broadcast channel that is shared among all downstream subscribers. */
    private val broadcastChannel = BroadcastChannel<T>(capacity)

    /** Internal state tracking whether the instance is already closed. */
    private val active = kotlinx.atomicfu.atomic(true)

    /** Command channel to send inputs to internal counter actor coroutine. */
    private val publishCounterCommands = Channel<Boolean>()

    /** Notification channel for counter actor coroutine to broadcast changes to counter value. */
    private val publishCounterMessages = newPublishCounter(publishCounterCommands)

    /** Internal [Publisher] instance for all [Subscriber]s. */
    private val publisher: Publisher<T> = publish {
        withContext(publishContext) {
            val receiveChannel = broadcastChannel.openSubscription()

            // Notify counter to increment publish count.
            publishCounterCommands.sendMessage(true)

            // Loop over incoming signals until upstream closes.
            for (element in receiveChannel) {
                send(element)
            }

            // Notify counter to decrement publish count.
            publishCounterCommands.sendMessage(false)
        }
    }

    override fun subscribe(subscriber: Subscriber<in T>?) {
        if (subscriber == null) {
            // Reactive Stream standard requires that NPE be thrown if subscriber is null.
            // (Publisher Rule 9)
            throw NullPointerException("Subscriber may not be null.")
        }

        publisher.subscribe(subscriber)
    }

    /**
     * Return a [Deferred] handle to wait on the condition when this [Publisher] instance turns
     * hot due to incoming subscription.
     *
     * @return
     *   a [Deferred] instance that returns `true` for [Deferred.isCompleted] when this publisher
     *   instance is subscribed to.
     */
    fun waitForSubscription(waitCount: Int = 1): Deferred<Unit> {
        val notifications = publishCounterMessages.openSubscription()

        return async {
            for (counterValue in notifications) {
                if (counterValue >= waitCount) {
                    break
                }
            }
        }
    }

    /**
     * Broadcast the input element as the next signal to all downstream subscribers. If there is
     * no active subscriber, the signal is lost / dropped.
     *
     * @param[element]
     *   element to broadcast.
     *
     * @return
     *   `true` if element was broadcast, `false` otherwise.
     */
    fun broadcast(element: T): Boolean {
        // To prevent the caller from being stalled / suspended, we use offer() instead of send() to
        // detect whether the element can actually be sent to downstream.
        return try {
            broadcastChannel.offer(element)
        } catch (error: Throwable) {
            // If the broadcast offered failed for any reason (e.g. channel closed), return false.
            false
        }
    }

    /**
     * Explicitly close the publisher instance. All non-fully consumed subscriptions will be
     * served cancellation signal.
     *
     * Note: The instance can only be closed once. All subsequent calls after initial [close] have
     * no effect.
     */
    fun close(error: Throwable? = null) {
        active.getAndSet(false)
                .takeIf { it } // Proceed if went from false -> true.
                ?.run {
                    broadcastChannel.close(error)
                    publishCounterCommands.close(error)
                    job.cancel()
                }
    }

    /**
     * Create a new "counter" actor coroutine that keeps track of the number of times this publisher
     * instance has been subscribed to, and emits broadcast notification signals for any downstream
     * listeners.
     *
     * @param[channel]
     *   input "command" channel to increment the counter.
     *
     * @return
     *   the notification channel to broadcast changes to the counter value.
     */
    private fun newPublishCounter(channel: ReceiveChannel<Boolean>): BroadcastChannel<Int> {
        val notificationChannel = BroadcastChannel<Int>(Channel.CONFLATED)

        launch {
            var counter = 0
            for (message in channel) {
                // true => increment and false => decrement.
                counter += if (message) 1 else -1
                notificationChannel.offer(counter)
            }
        }.invokeOnCompletion {
            // Close the notification channel on exit.
            notificationChannel.close()
        }

        return notificationChannel
    }

    /**
     * Extension to [Channel] to send a message and ignore any potential
     * [ClosedSendChannelException] failures due to channel being closed.
     *
     * @param[T]
     *   type of the message to send.
     * @param[message]
     *   message to send on the channel.
     */
    private suspend inline fun <T> Channel<T>.sendMessage(message: T) {
        try {
            send(message)
        }
        catch (error: ClosedSendChannelException) { }
    }
}
