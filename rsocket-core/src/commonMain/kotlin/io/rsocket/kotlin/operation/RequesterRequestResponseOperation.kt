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

package io.rsocket.kotlin.operation

import io.rsocket.kotlin.frame.*
import io.rsocket.kotlin.internal.io.*
import io.rsocket.kotlin.payload.*
import kotlinx.coroutines.*

internal class RequesterRequestResponseOperation(
    private val responseDeferred: CompletableDeferred<Payload>,
) : RequesterOperation {

    override suspend fun execute(outbound: OperationOutbound, requestPayload: Payload) {
        try {
            outbound.sendRequest(
                type = FrameType.RequestResponse,
                payload = requestPayload,
                complete = false,
                initialRequest = 0
            )
            responseDeferred.join()
        } catch (cause: Throwable) {
            nonCancellable { outbound.sendCancel() }
            throw cause
        }
    }

    override fun shouldReceiveFrame(frameType: FrameType): Boolean = when {
        responseDeferred.isActive -> frameType === FrameType.Payload || frameType === FrameType.Error
        else                      -> false
    }

    override fun receivePayloadFrame(payload: Payload?, complete: Boolean) {
        if (payload != null) {
            if (!responseDeferred.complete(payload)) payload.close()
        } else {
            responseDeferred.completeExceptionally(
                IllegalStateException("Unexpected request completion: payload should be present for RequestResponse")
            )
        }
    }

    override fun receiveErrorFrame(cause: Throwable) {
        responseDeferred.completeExceptionally(cause)
    }

    override fun receiveDone() {
        if (responseDeferred.isActive) responseDeferred.completeExceptionally(
            IllegalStateException("Unexpected request completion: no response received")
        )
    }

    override fun operationFailure(cause: Throwable) {
        if (responseDeferred.isActive) responseDeferred.completeExceptionally(cause)
    }
}
