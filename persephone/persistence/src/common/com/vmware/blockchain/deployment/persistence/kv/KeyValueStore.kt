/* **************************************************************************
 * Copyright (c) 2019 VMware, Inc.  All rights reserved. VMware Confidential
 * *************************************************************************/
package com.vmware.blockchain.deployment.persistence.kv

import com.vmware.blockchain.deployment.reactive.Publisher

/**
 * Typed shim abstraction for key-value storage.
 *
 * @param[K]
 *   type of key for the key-value store.
 * @param[V]
 *   type of value for the key-value store.
 * @param[T]
 *   subtype of [Version] to use to version the key-value entries.
 */
interface KeyValueStore<K, V, T : KeyValueStore.Version<T>> {

    /**
     * Contract for an untyped entity that is binary-representable.
     */
    interface Value {
        /**
         * Convert the entity to its binary representation.
         */
        fun asByteArray(): ByteArray
    }

    /**
     * Contract for a binary-representable version type that can be compared against other instances
     * of the same type for inequality.
     */
    interface Version<T> : Value, Comparable<T> {
        /**
         * Create a new instance of the same type representing the current instance's logical next
         * value.
         */
        fun next(): T
    }

    sealed class Versioned<out V, out T : Version<out T>> {
        data class Just<V, out T : Version<out T>>(val value: V, val version: T) : Versioned<V, T>()
        object None : Versioned<Nothing, Nothing>()

        fun <U> map(transform: (V) -> U): Versioned<U, T> {
            return when (this) {
                is Just<V, T> -> Just(transform(value), version)
                is None -> None
            }
        }

        fun <U, S : Version<S>> map(
            valueTransform: (V) -> U,
            versionTransform: (T) -> S
        ): Versioned<U, S> {
            return when (this) {
                is Just<V, T> -> Just(valueTransform(value), versionTransform(version))
                is None -> None
            }
        }

        fun <U, S : Version<S>> flatMap(transform: (V, T) -> Versioned<U, S>): Versioned<U, S> {
            return when (this) {
                is Just<V, T> -> transform(value, version)
                is None -> None
            }
        }
    }

    sealed class Event<K, V, T> {
        data class ChangeEvent<K, V, T>(
            val key: K,
            val value: V,
            val version: Version<T>
        ): Event<K, V, T>()
        data class DeleteEvent<K, V, T>(
            val key: K,
            val lastVersion: Version<T>
        ): Event<K, V, T>()
    }

    /**
     * Shutdown the [KeyValueStore] instance and closes all resources.
     */
    fun close()

    /**
     * Retrieve the value associated with the [key].
     *
     * @param[key]
     *   key to look up.
     *
     * @return
     *   a [Publisher] that publishes the current value with its version if the key exists,
     *   otherwise publishes [Versioned.None] if the key was not found.
     */
    operator fun get(key: K): Publisher<Versioned<V, T>>

    /**
     * Associate the [value] with the given [key], provided that the [expected] version
     * matches the current version associated with the key.
     *
     * @param[key]
     *   key to store the associated value.
     * @param[expected]
     *   expected current / latest version in the store.
     * @param[value]
     *   value to be stored.
     *
     * @return
     *   a [Publisher] that publishes the previous value with its version if update was successful,
     *   or publishes [Versioned.None] if there was no previous value associated, or publishes an
     *   [IllegalStateException] if update fails due to version mismatch.
     */
    fun set(key: K, expected: Version<T>, value: V): Publisher<Versioned<V, T>>

    /**
     * Retrieve the key-value association for a given [key].
     *
     * @param[key]
     *   key of the entry to delete.
     *
     * @return
     *   a [Publisher] that publishes the last value with its version if delete was successful,
     *   or publishes [Versioned.None] if key was not found, or publishes an [IllegalStateException]
     *   if delete fails due to version mismatch.
     */
    fun delete(key: K, expected: Version<T>): Publisher<Versioned<V, T>>

    /**
     * Create an event-sourcing publication channel.
     *
     * @param[capacity]
     *   number of events that the channel can buffer before becoming full.
     * @param[state]
     *   whether current state expressed as subset of prior mutation events should also be emitted
     *   as a prefix stream of events.
     *
     * @return
     *   a [Publisher] that publishes all state-mutation events to the key-value store.
     */
    fun subscribe(capacity: Int, state: Boolean): Publisher<Event<K, V, T>>

    /**
     * Unsubscribe an instance of event-sourced [Publisher] if the instance was previously returned
     * from [subscribe].
     *
     * Note: The operation has no effect if the supplied [Publisher] instance was not previously
     * actively subscribing (e.g. already subscribed or was never an associated event sink channel).
     */
    fun unsubscribe(eventSink: Publisher<Event<K, V, T>>)
}