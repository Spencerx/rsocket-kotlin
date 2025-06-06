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

package io.rsocket.kotlin.transport.ktor.websocket.server

import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.rsocket.kotlin.internal.io.*
import io.rsocket.kotlin.transport.*
import io.rsocket.kotlin.transport.ktor.websocket.internal.*
import kotlinx.coroutines.*
import kotlin.coroutines.*

@OptIn(RSocketTransportApi::class)
public sealed interface KtorWebSocketServerInstance : RSocketServerInstance {
    public val connectors: List<EngineConnectorConfig>
    public val path: String
    public val protocol: String?
}

@OptIn(RSocketTransportApi::class)
public sealed interface KtorWebSocketServerTransport : RSocketTransport {

    public fun target(
        host: String = "0.0.0.0",
        port: Int = 80,
        path: String = "",
        protocol: String? = null,
    ): RSocketServerTarget<KtorWebSocketServerInstance>

    public fun target(
        path: String = "",
        protocol: String? = null,
        connectorBuilder: EngineConnectorBuilder.() -> Unit,
    ): RSocketServerTarget<KtorWebSocketServerInstance>

    public fun target(
        connector: EngineConnectorConfig,
        path: String = "",
        protocol: String? = null,
    ): RSocketServerTarget<KtorWebSocketServerInstance>

    public fun target(
        connectors: List<EngineConnectorConfig>,
        path: String = "",
        protocol: String? = null,
    ): RSocketServerTarget<KtorWebSocketServerInstance>

    public companion object Factory :
        RSocketTransportFactory<KtorWebSocketServerTransport, KtorWebSocketServerTransportBuilder>(::KtorWebSocketServerTransportBuilderImpl)
}

@OptIn(RSocketTransportApi::class)
public sealed interface KtorWebSocketServerTransportBuilder : RSocketTransportBuilder<KtorWebSocketServerTransport> {
    public fun <A : ApplicationEngine, T : ApplicationEngine.Configuration> httpEngine(
        factory: ApplicationEngineFactory<A, T>,
        configure: T.() -> Unit = {},
    )

    public fun webSocketsConfig(block: WebSockets.WebSocketOptions.() -> Unit)
}

private class KtorWebSocketServerTransportBuilderImpl : KtorWebSocketServerTransportBuilder {
    private var httpServerFactory: HttpServerFactory<*, *>? = null
    private var webSocketsConfig: WebSockets.WebSocketOptions.() -> Unit = {}

    override fun <A : ApplicationEngine, T : ApplicationEngine.Configuration> httpEngine(
        factory: ApplicationEngineFactory<A, T>,
        configure: T.() -> Unit,
    ) {
        this.httpServerFactory = HttpServerFactory(factory, configure)
    }

    override fun webSocketsConfig(block: WebSockets.WebSocketOptions.() -> Unit) {
        this.webSocketsConfig = block
    }

    @RSocketTransportApi
    override fun buildTransport(context: CoroutineContext): KtorWebSocketServerTransport = KtorWebSocketServerTransportImpl(
        coroutineContext = context.supervisorContext() + Dispatchers.Default,
        factory = requireNotNull(httpServerFactory) { "httpEngine is required" },
        webSocketsConfig = webSocketsConfig,
    )
}

private class KtorWebSocketServerTransportImpl(
    override val coroutineContext: CoroutineContext,
    private val factory: HttpServerFactory<*, *>,
    private val webSocketsConfig: WebSockets.WebSocketOptions.() -> Unit,
) : KtorWebSocketServerTransport {
    override fun target(
        connectors: List<EngineConnectorConfig>,
        path: String,
        protocol: String?,
    ): RSocketServerTarget<KtorWebSocketServerInstance> = KtorWebSocketServerTargetImpl(
        coroutineContext = coroutineContext.supervisorContext(),
        factory = factory,
        webSocketsConfig = webSocketsConfig,
        connectors = connectors,
        path = path,
        protocol = protocol
    )

    override fun target(
        host: String,
        port: Int,
        path: String,
        protocol: String?,
    ): RSocketServerTarget<KtorWebSocketServerInstance> = target(path, protocol) {
        this.host = host
        this.port = port
    }

    override fun target(
        path: String,
        protocol: String?,
        connectorBuilder: EngineConnectorBuilder.() -> Unit,
    ): RSocketServerTarget<KtorWebSocketServerInstance> = target(EngineConnectorBuilder().apply(connectorBuilder), path, protocol)

    override fun target(
        connector: EngineConnectorConfig,
        path: String,
        protocol: String?,
    ): RSocketServerTarget<KtorWebSocketServerInstance> = target(listOf(connector), path, protocol)
}

@OptIn(RSocketTransportApi::class)
private class KtorWebSocketServerTargetImpl(
    override val coroutineContext: CoroutineContext,
    private val factory: HttpServerFactory<*, *>,
    private val webSocketsConfig: WebSockets.WebSocketOptions.() -> Unit,
    private val connectors: List<EngineConnectorConfig>,
    private val path: String,
    private val protocol: String?,
) : RSocketServerTarget<KtorWebSocketServerInstance> {

    @RSocketTransportApi
    override suspend fun startServer(onConnection: (RSocketConnection) -> Unit): KtorWebSocketServerInstance {
        currentCoroutineContext().ensureActive()
        coroutineContext.ensureActive()

        val serverContext = coroutineContext.childContext()
        val embeddedServer = createServer(serverContext, onConnection)
        val resolvedConnectors = startServer(embeddedServer, serverContext)

        return KtorWebSocketServerInstanceImpl(
            coroutineContext = serverContext,
            connectors = resolvedConnectors,
            path = path,
            protocol = protocol
        )
    }

    // parentCoroutineContext is the context of server instance
    @RSocketTransportApi
    private fun createServer(
        serverContext: CoroutineContext,
        onConnection: (RSocketConnection) -> Unit,
    ): EmbeddedServer<*, *> {
        val config = serverConfig {
            val target = this@KtorWebSocketServerTargetImpl
            parentCoroutineContext = serverContext
            module {
                install(WebSockets, webSocketsConfig)
                routing {
                    webSocket(target.path, target.protocol) {
                        onConnection(KtorWebSocketConnection(this))
                        awaitCancellation()
                    }
                }
            }
        }
        return factory.createServer(config, connectors)
    }

    private suspend fun startServer(
        embeddedServer: EmbeddedServer<*, *>,
        serverContext: CoroutineContext,
    ): List<EngineConnectorConfig> {
        @OptIn(DelicateCoroutinesApi::class)
        val serverJob = launch(serverContext, start = CoroutineStart.ATOMIC) {
            try {
                currentCoroutineContext().ensureActive() // because of atomic start
                embeddedServer.startSuspend()
                awaitCancellation()
            } finally {
                nonCancellable {
                    embeddedServer.stopSuspend()
                }
            }
        }
        return try {
            embeddedServer.engine.resolvedConnectors()
        } catch (cause: Throwable) {
            serverJob.cancel("Starting server cancelled", cause)
            throw cause
        }
    }
}

private class KtorWebSocketServerInstanceImpl(
    override val coroutineContext: CoroutineContext,
    override val connectors: List<EngineConnectorConfig>,
    override val path: String,
    override val protocol: String?,
) : KtorWebSocketServerInstance

private class HttpServerFactory<A : ApplicationEngine, T : ApplicationEngine.Configuration>(
    private val factory: ApplicationEngineFactory<A, T>,
    private val configure: T.() -> Unit = {},
) {
    fun createServer(
        config: ServerConfig,
        connectors: List<EngineConnectorConfig>,
    ): EmbeddedServer<A, T> = embeddedServer(factory, config) {
        this.connectors.addAll(connectors)
        this.configure()
    }
}
