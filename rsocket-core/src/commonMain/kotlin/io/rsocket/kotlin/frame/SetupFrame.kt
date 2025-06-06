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

package io.rsocket.kotlin.frame

import io.rsocket.kotlin.frame.io.*
import io.rsocket.kotlin.keepalive.*
import io.rsocket.kotlin.payload.*
import kotlinx.io.*

private const val HonorLeaseFlag = 64
private const val ResumeEnabledFlag = 128

internal class SetupFrame(
    val version: Version,
    val honorLease: Boolean,
    val keepAlive: KeepAlive,
    val resumeToken: Buffer?,
    val payloadMimeType: PayloadMimeType,
    val payload: Payload,
) : Frame() {
    override val type: FrameType get() = FrameType.Setup
    override val streamId: Int get() = 0
    override val flags: Int
        get() {
            var flags = 0
            if (honorLease) flags = flags or HonorLeaseFlag
            if (resumeToken != null) flags = flags or ResumeEnabledFlag
            if (payload.metadata != null) flags = flags or Flags.Metadata
            return flags
        }

    override fun close() {
        resumeToken?.close()
        payload.close()
    }

    override fun Sink.writeSelf() {
        writeVersion(version)
        writeInt(keepAlive.intervalMillis)
        writeInt(keepAlive.maxLifetimeMillis)
        writeResumeToken(resumeToken)
        writeStringMimeType(payloadMimeType.metadata)
        writeStringMimeType(payloadMimeType.data)
        writePayload(payload)
    }

    override fun StringBuilder.appendFlags() {
        appendFlag('M', payload.metadata != null)
        appendFlag('R', resumeToken != null)
        appendFlag('L', honorLease)
    }

    override fun StringBuilder.appendSelf() {
        append("\nVersion: ").append(version.toString()).append(" Honor lease: ").append(honorLease).append("\n")
        append("Keep alive: interval=").append(keepAlive.intervalMillis).append(" ms,")
        append("max lifetime=").append(keepAlive.maxLifetimeMillis).append(" ms\n")
        append("Data mime type: ").append(payloadMimeType.data).append("\n")
        append("Metadata mime type: ").append(payloadMimeType.metadata)
        appendPayload(payload)
    }
}

internal fun Buffer.readSetup(flags: Int): SetupFrame {
    val version = readVersion()
    val keepAlive = run {
        val interval = readInt()
        val maxLifetime = readInt()
        KeepAlive(intervalMillis = interval, maxLifetimeMillis = maxLifetime)
    }
    val resumeToken = if (flags check ResumeEnabledFlag) readResumeToken() else null
    val payloadMimeType = run {
        val metadata = readStringMimeType()
        val data = readStringMimeType()
        PayloadMimeType(data = data, metadata = metadata)
    }
    val payload = readPayload(flags)
    return SetupFrame(
        version = version,
        honorLease = flags check HonorLeaseFlag,
        keepAlive = keepAlive,
        resumeToken = resumeToken,
        payloadMimeType = payloadMimeType,
        payload = payload
    )
}

private fun Buffer.readStringMimeType(): String {
    val length = readByte().toLong()
    return readString(length)
}

private fun Sink.writeStringMimeType(mimeType: String) {
    val bytes = mimeType.encodeToByteArray()
    writeByte(bytes.size.toByte())
    write(bytes)
}
