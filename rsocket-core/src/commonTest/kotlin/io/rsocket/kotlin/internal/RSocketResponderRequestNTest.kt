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

package io.rsocket.kotlin.internal

import app.cash.turbine.*
import io.rsocket.kotlin.*
import io.rsocket.kotlin.frame.*
import io.rsocket.kotlin.frame.io.*
import io.rsocket.kotlin.keepalive.*
import io.rsocket.kotlin.payload.*
import io.rsocket.kotlin.test.*
import io.rsocket.kotlin.transport.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.*
import kotlinx.coroutines.flow.*
import kotlin.coroutines.*
import kotlin.test.*

class RSocketResponderRequestNTest : TestWithConnection() {
    private val testJob: Job = Job()

    private class TestInstance(val deferred: Deferred<Unit>) : RSocketServerInstance {
        override val coroutineContext: CoroutineContext get() = deferred
    }

    private class TestServer(
        override val coroutineContext: CoroutineContext,
        private val connection: RSocketConnection,
    ) : RSocketServerTarget<TestInstance> {
        override suspend fun startServer(onConnection: (RSocketConnection) -> Unit): TestInstance {
            return TestInstance(async { onConnection(connection) })
        }
    }

    private suspend fun start(handler: RSocket) {
        TestServer().startServer(
            TestServer(Dispatchers.Unconfined + testJob + TestExceptionHandler, connection)
        ) {
            config.setupPayload.close()
            handler
        }
    }

    override suspend fun after() {
        super.after()
        testJob.cancelAndJoin()
    }

    private val setupFrame
        get() = SetupFrame(
            version = Version.Current,
            honorLease = false,
            keepAlive = DefaultKeepAlive,
            resumeToken = null,
            payloadMimeType = DefaultPayloadMimeType,
            payload = payload("setup"),
        )

    @Test
    fun testStreamInitialEnoughToConsume() = test {
        start(
            RSocketRequestHandler {
                requestStream { payload ->
                    payload.close()
                    (0..9).asFlow().map { buildPayload { data("$it") } }
                }
            }
        )

        connection.test {
            connection.sendToReceiver(setupFrame)

            connection.sendToReceiver(
                RequestStreamFrame(
                    initialRequestN = 16,
                    streamId = 1,
                    payload = payload("request")
                )
            )

            awaitAndReleasePayloadFrames(amount = 10)
            awaitCompleteFrame()
            expectNoEventsIn(200)
        }
    }

    @Test
    fun testStreamSuspendWhenNoRequestsLeft() = test {
        var lastSent = -1
        start(
            RSocketRequestHandler {
                requestStream { payload ->
                    payload.close()
                    (0..9).asFlow()
                        .onEach { lastSent = it }
                        .map { buildPayload { data("$it") } }
                }
            }
        )

        connection.test {
            connection.sendToReceiver(setupFrame)

            connection.sendToReceiver(
                RequestStreamFrame(
                    initialRequestN = 3,
                    streamId = 1,
                    payload = payload("request")
                )
            )

            awaitAndReleasePayloadFrames(amount = 3)
            expectNoEventsIn(200)
            assertEquals(3, lastSent)
        }
    }

    @Test
    fun testStreamRequestNFrameResumesOperation() = test {
        start(
            RSocketRequestHandler {
                requestStream { payload ->
                    payload.close()
                    (0..15).asFlow().map { buildPayload { data("$it") } }
                }
            }
        )
        connection.test {
            connection.sendToReceiver(setupFrame)

            connection.sendToReceiver(
                RequestStreamFrame(
                    initialRequestN = 3,
                    streamId = 1,
                    payload = payload("request")
                )
            )
            awaitAndReleasePayloadFrames(amount = 3)
            expectNoEventsIn(200)

            connection.sendToReceiver(RequestNFrame(streamId = 1, requestN = 5))
            awaitAndReleasePayloadFrames(amount = 5)
            expectNoEventsIn(200)

            connection.sendToReceiver(RequestNFrame(streamId = 1, requestN = 5))
            awaitAndReleasePayloadFrames(amount = 5)
            expectNoEventsIn(200)
        }
    }

    @Test
    fun testStreamRequestNEnoughToComplete() = test {
        val total = 20
        start(
            RSocketRequestHandler {
                requestStream { payload ->
                    payload.close()
                    (0 until total).asFlow().map { buildPayload { data("$it") } }
                }
            }
        )
        connection.test {
            connection.sendToReceiver(setupFrame)

            val firstRequest = 3
            connection.sendToReceiver(
                RequestStreamFrame(
                    initialRequestN = firstRequest,
                    streamId = 1,
                    payload = payload("request")
                )
            )
            awaitAndReleasePayloadFrames(amount = firstRequest)
            expectNoEventsIn(200)

            connection.sendToReceiver(RequestNFrame(streamId = 1, requestN = Int.MAX_VALUE))
            awaitAndReleasePayloadFrames(amount = total - firstRequest)
            awaitCompleteFrame()
            expectNoEventsIn(200)
        }
    }

    @Test
    fun testStreamRequestNAttemptedIntOverflow() = test {
        val latch = Channel<Unit>(1)
        start(
            RSocketRequestHandler {
                requestStream { payload ->
                    payload.close()
                    latch.receive()
                    // make sure limiter has got the RequestNFrame before emitting the values
                    delay(200)
                    (0..19).asFlow().map { buildPayload { data("$it") } }
                }
            }
        )
        connection.test {
            connection.sendToReceiver(setupFrame)

            connection.sendToReceiver(
                RequestStreamFrame(
                    initialRequestN = Int.MAX_VALUE,
                    streamId = 1,
                    payload = payload("request")
                )
            )
            connection.sendToReceiver(RequestNFrame(streamId = 1, requestN = Int.MAX_VALUE))
            latch.send(Unit)

            awaitAndReleasePayloadFrames(amount = 20)
            awaitCompleteFrame()
            expectNoEventsIn(200)
        }
    }


    @Test
    fun testStreamRequestNSummingUpToOverflow() = test {
        val latch = Channel<Unit>(1)
        start(
            RSocketRequestHandler {
                requestStream { payload ->
                    payload.close()
                    latch.receive()
                    // make sure limiter has got the RequestNFrame before emitting the values
                    delay(200)
                    (0..19).asFlow().map { buildPayload { data("$it") } }
                }
            }
        )

        connection.test {
            connection.sendToReceiver(setupFrame)

            connection.sendToReceiver(
                RequestStreamFrame(
                    initialRequestN = 5,
                    streamId = 1,
                    payload = payload("request")
                )
            )
            connection.sendToReceiver(RequestNFrame(streamId = 1, requestN = Int.MAX_VALUE / 3))
            connection.sendToReceiver(RequestNFrame(streamId = 1, requestN = Int.MAX_VALUE / 3))
            connection.sendToReceiver(RequestNFrame(streamId = 1, requestN = Int.MAX_VALUE / 3))
            connection.sendToReceiver(RequestNFrame(streamId = 1, requestN = Int.MAX_VALUE / 3))
            latch.send(Unit)

            awaitAndReleasePayloadFrames(amount = 20)
            awaitCompleteFrame()
            expectNoEventsIn(200)
        }
    }

    private suspend fun ReceiveTurbine<Frame>.awaitAndReleasePayloadFrames(amount: Int) {
        repeat(amount) {
            awaitFrame { frame ->
                assertTrue(frame is RequestFrame)
                assertEquals(FrameType.Payload, frame.type)
                frame.payload.close()
            }
        }
    }

    private suspend fun ReceiveTurbine<Frame>.awaitCompleteFrame() {
        awaitFrame { frame ->
            assertTrue(frame is RequestFrame)
            assertEquals(FrameType.Payload, frame.type)
            assertTrue(frame.complete, "Frame should be complete")
        }
    }
}
