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

import kotlinx.io.*

internal class ResumeOkFrame(
    val lastReceivedClientPosition: Long,
) : Frame() {
    override val type: FrameType get() = FrameType.ResumeOk
    override val streamId: Int get() = 0
    override val flags: Int get() = 0

    override fun close(): Unit = Unit

    override fun Sink.writeSelf() {
        writeLong(lastReceivedClientPosition)
    }

    override fun StringBuilder.appendFlags(): Unit = Unit

    override fun StringBuilder.appendSelf() {
        append("\nLast received client position: ").append(lastReceivedClientPosition)
    }
}

internal fun Buffer.readResumeOk(): ResumeOkFrame = ResumeOkFrame(readLong())
