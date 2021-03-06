/*
 * Copyright 2016 Netflix, Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package io.rsocket.kotlin.interceptors

import io.rsocket.kotlin.InterceptorOptions
import io.rsocket.kotlin.internal.InterceptorRegistry

/** JVM wide interceptors for RSocket  */
object GlobalInterceptors : InterceptorOptions {
    private val DEFAULT = InterceptorRegistry()

    override fun connection(interceptor: DuplexConnectionInterceptor) {
        DEFAULT.connection(interceptor)
    }

    override fun requester(interceptor: RSocketInterceptor) {
        DEFAULT.requester(interceptor)
    }

    override fun handler(interceptor: RSocketInterceptor) {
        DEFAULT.handler(interceptor)
    }

    internal fun create(): InterceptorRegistry = InterceptorRegistry(DEFAULT)
}
