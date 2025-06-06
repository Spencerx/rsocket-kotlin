/*
 * Copyright 2015-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.rsocket.kotlin.transport.nodejs.tcp

import io.rsocket.kotlin.*
import io.rsocket.kotlin.internal.io.*
import io.rsocket.kotlin.transport.nodejs.tcp.internal.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.*
import kotlinx.io.*
import kotlin.coroutines.*

@Suppress("DEPRECATION_ERROR")
internal class TcpConnection(
    override val coroutineContext: CoroutineContext,
    private val socket: Socket,
) : Connection {

    private val sendChannel = bufferChannel(8)
    private val receiveChannel = bufferChannel(Channel.UNLIMITED)

    init {
        launch {
            sendChannel.consumeEach(socket::writeFrame)
        }

        coroutineContext.job.invokeOnCompletion {
            when (it) {
                null -> socket.destroy()
                else -> socket.destroy(Error(it.message, it.cause))
            }
        }

        val frameAssembler = FrameWithLengthAssembler { receiveChannel.trySend(it) }
        socket.on(
            onData = frameAssembler::write,
            onError = { coroutineContext.job.cancel("Socket error", it) },
            onClose = { if (!it) coroutineContext.job.cancel("Socket closed") }
        )
    }

    override suspend fun send(packet: Buffer) {
        sendChannel.send(packet)
    }

    override suspend fun receive(): Buffer {
        return receiveChannel.receive()
    }
}
