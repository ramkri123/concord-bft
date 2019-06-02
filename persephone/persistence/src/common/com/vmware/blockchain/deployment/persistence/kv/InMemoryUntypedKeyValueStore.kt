/* **************************************************************************
 * Copyright (c) 2019 VMware, Inc.  All rights reserved. VMware Confidential
 * *************************************************************************/
package com.vmware.blockchain.deployment.persistence.kv

import com.vmware.blockchain.deployment.persistence.kv.KeyValueStore.Event
import com.vmware.blockchain.deployment.persistence.kv.KeyValueStore.Value
import com.vmware.blockchain.deployment.persistence.kv.KeyValueStore.Version
import com.vmware.blockchain.deployment.persistence.kv.KeyValueStore.Versioned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.launch
import kotlinx.serialization.KSerializer
import kotlin.coroutines.CoroutineContext

/**
 * In-memory implementation of [UntypedKeyValueStore] interface.
 *
 * @param[T]
 *   subtype of [Version] to use to version the key-value entries.
 * @param[context]
 *   coroutine context to use as basis for underlying coroutine-based operations.
 */
class InMemoryUntypedKeyValueStore<T : Version<T>>(
    context: CoroutineContext = Dispatchers.Default,
    versionSerializer: KSerializer<T>
) : AbstractUntypedKeyValueStore<T>(context, versionSerializer) {

    /**
     * An actor coroutine that maintains internal in-memory versioned key-value storage as state.
     *
     * Note: By design this function should be invoked at most once for the lifetime of an instance.
     */
    override fun newStorageServer(): Channel<UntypedKeyValueStore.Request<T>> {
        // Request channel to send to the storage server.
        val requestChannel = Channel<UntypedKeyValueStore.Request<T>>(Channel.RENDEZVOUS)

        launch(coroutineContext) {
            // Internal server storage (non-concurrent because access to it is exclusive).
            val storage = mutableMapOf<Value, Versioned.Just<Value, T>>()
            val eventSinks = mutableListOf<Channel<Event<Value, Value, T>>>()

            // Loop until the channel closes.
            for (message in requestChannel) {
                when (message) {
                    is UntypedKeyValueStore.Request.Get<T> -> {
                        val responseMessage: UntypedKeyValueStore.Response<T> = storage[message.key]
                                ?.let { UntypedKeyValueStore.Response.Get(it) }
                                ?: UntypedKeyValueStore.Response.Get(Versioned.None)

                        // Send the response back.
                        message.response.send(responseMessage)
                    }
                    is UntypedKeyValueStore.Request.Set<T> -> {
                        val existing = storage[message.key]
                        val responseMessage: UntypedKeyValueStore.Response<T> = when {
                            existing == null -> {
                                Versioned.Just(message.value, message.expected.next())
                                        .apply { storage[message.key] = this }
                                        .let { Event.ChangeEvent(message.key, it.value, it.version) }
                                        .apply { offerEvent(eventSinks, this) }
                                UntypedKeyValueStore.Response.Set(Versioned.None)
                            }
                            existing.version == message.expected -> {
                                Versioned.Just(message.value, message.expected.next())
                                        .apply { storage[message.key] = this }
                                        .let { Event.ChangeEvent(message.key, it.value, it.version) }
                                        .apply { offerEvent(eventSinks, this) }
                                UntypedKeyValueStore.Response.Set(existing)
                            }
                            else -> UntypedKeyValueStore.Response.Error(
                                    VersionMismatchException(message.expected, existing.version)
                            )
                        }

                        // Send the response back.
                        message.response.send(responseMessage)
                    }
                    is UntypedKeyValueStore.Request.Delete<T> -> {
                        val existing = storage[message.key]
                        val responseMessage: UntypedKeyValueStore.Response<T> = when {
                            existing == null -> UntypedKeyValueStore.Response.Set(Versioned.None)
                            existing.version == message.expected -> {
                                storage.remove(message.key, existing)
                                Event.DeleteEvent<Value, Value, T>(message.key, existing.version)
                                        .apply { offerEvent(eventSinks, this) }
                                UntypedKeyValueStore.Response.Delete(existing)
                            }
                            else -> UntypedKeyValueStore.Response.Error(
                                    VersionMismatchException(message.expected, existing.version)
                            )
                        }

                        // Send the response back.
                        message.response.send(responseMessage)
                    }
                    is UntypedKeyValueStore.Request.Subscribe<T> -> {
                        val eventSink = Channel<Event<Value, Value, T>>(Channel.UNLIMITED)
                                .apply {
                                    // If history was requested, stream back current state as a
                                    // reconstructed series of mutation events.
                                    //
                                    // Note: In the general case for off-heap storage, event
                                    // reconstruction is not necessarily possible or even correct,
                                    // depending on the underlying data representation in the data
                                    // store.
                                    if (message.state) {
                                        for (entry in storage) {
                                            val event = Event.ChangeEvent(
                                                    entry.key,
                                                    entry.value.value,
                                                    entry.value.version
                                            )

                                            offerEvent(this, event)
                                        }
                                    }
                                }
                                .also { eventSinks += it }

                        // Send the response back.
                        val responseMessage = UntypedKeyValueStore.Response.Subscribe(eventSink)
                        message.response.send(responseMessage)
                    }
                    is UntypedKeyValueStore.Request.Unsubscribe<T> -> {
                        // Remove the subscription from the list and close that channel.
                        message.subscription
                                .takeIf { eventSinks.remove(it) }
                                ?.apply { close() }
                    }
                }
            }
        }

        return requestChannel
    }

    /**
     * Offer a given event to downstream channels by invoking [Channel.offer]. The function does not
     * check for return value (failed offers are treated as drops).
     */
    private fun offerEvent(
        sinks: Iterable<SendChannel<Event<Value, Value, T>>>,
        event: Event<Value, Value, T>
    ) {
        for (sink in sinks) {
            offerEvent(sink, event)
        }
    }

    private fun offerEvent(
        sink: SendChannel<Event<Value, Value, T>>,
        event: Event<Value, Value, T>
    ) {
        if (!sink.offer(event)) {
            // The event stream requires continuity once started, failing to send an element would
            // constitute a lossy stream.
            // For now, terminating the send channel would ensure the assumption on consumption side
            // is still upheld.
            sink.close(InterruptedEventStreamException)
        }
    }
}
