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

package io.rsocket.kotlin.ktor.tests

import io.ktor.client.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.routing.*
import io.rsocket.kotlin.*
import io.rsocket.kotlin.keepalive.*
import io.rsocket.kotlin.ktor.client.*
import io.rsocket.kotlin.ktor.server.*
import io.rsocket.kotlin.payload.*
import io.rsocket.kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.test.*
import io.ktor.client.engine.cio.CIO as ClientCIO
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.server.cio.CIO as ServerCIO
import io.ktor.server.websocket.WebSockets as ServerWebSockets
import io.rsocket.kotlin.ktor.client.RSocketSupport as ClientRSocketSupport
import io.rsocket.kotlin.ktor.server.RSocketSupport as ServerRSocketSupport

class WebSocketConnectionTest : SuspendTest {
    private val client = HttpClient(ClientCIO) {
        install(ClientWebSockets)
        install(ClientRSocketSupport) {
            connector(
                TestConnector {
                    connectionConfig {
                        keepAlive = KeepAlive(500)
                    }
                }
            )
        }
    }

    private var responderJob: Job? = null

    private val server = embeddedServer(ServerCIO, port = 0) {
        install(ServerWebSockets)
        install(ServerRSocketSupport) {
            server(TestServer())
        }
        routing {
            rSocket {
                RSocketRequestHandler {
                    requestStream {
                        it.close()
                        flow {
                            var i = 0
                            while (true) {
                                emit(buildPayload { data((++i).toString()) })
                                delay(1000)
                            }
                        }
                    }
                }.also { responderJob = it.coroutineContext.job }
            }
        }
    }

    override suspend fun before() {
        server.startSuspend()
        delay(1000)
    }

    override suspend fun after() {
        server.stopSuspend()
        client.coroutineContext.job.cancelAndJoin()
    }

    @Test
    fun testWorks() = test {
        val port = server.engine.resolvedConnectors().single().port
        val rSocket = client.rSocket(port = port)
        val requesterJob = rSocket.coroutineContext.job

        rSocket
            .requestStream(Payload.Empty)
            .take(2)
            .onEach { delay(100); it.close() }
            .collect()

        assertTrue(requesterJob.isActive)
        assertTrue(responderJob?.isActive!!)
    }
}
