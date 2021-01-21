//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.websocket.common.test;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.BiFunction;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.HttpConnection;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.server.handler.DefaultHandler;
import org.eclipse.jetty.server.handler.HandlerList;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.WebSocketBehavior;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.api.extensions.ExtensionConfig;
import org.eclipse.jetty.websocket.common.AcceptHash;
import org.eclipse.jetty.websocket.common.extensions.ExtensionStack;
import org.eclipse.jetty.websocket.common.extensions.WebSocketExtensionFactory;
import org.eclipse.jetty.websocket.common.scopes.SimpleContainerScope;
import org.eclipse.jetty.websocket.common.scopes.WebSocketContainerScope;

/**
 * A Server capable of WebSocket upgrade useful for testing.
 * <p>
 * This implementation exists to allow for testing of non-standard server behaviors,
 * especially around the WebSocket Upgrade process.
 */
public class BlockheadServer
{
    private static final Logger LOG = Log.getLogger(BlockheadServer.class);
    public static final String SEC_WEBSOCKET_EXTENSIONS = HttpHeader.SEC_WEBSOCKET_EXTENSIONS.toString();

    private final Server server;
    private final ServerConnector connector;
    private final BlockheadServerHandler serverHandler;
    private final WebSocketPolicy policy;
    private final WebSocketContainerScope websocketContainer;
    private final WebSocketExtensionFactory extensionFactory;
    private URI wsUri;

    public BlockheadServer()
    {
        this.server = new Server();
        this.connector = new ServerConnector(this.server);
        this.connector.setPort(0);
        this.server.addConnector(connector);

        this.policy = new WebSocketPolicy(WebSocketBehavior.SERVER);
        this.websocketContainer = new SimpleContainerScope(policy);
        this.extensionFactory = new WebSocketExtensionFactory(websocketContainer);

        HandlerList handlers = new HandlerList();
        this.serverHandler = new BlockheadServerHandler(websocketContainer, extensionFactory);
        handlers.addHandler(this.serverHandler);
        handlers.addHandler(new DefaultHandler());
        this.server.setHandler(handlers);
    }

    public void addConnectFuture(CompletableFuture<BlockheadConnection> serverConnFut)
    {
        this.serverHandler.getWSConnectionFutures().offer(serverConnFut);
    }

    public WebSocketExtensionFactory getExtensionFactory()
    {
        return extensionFactory;
    }

    public WebSocketPolicy getPolicy()
    {
        return policy;
    }

    public WebSocketContainerScope getWebsocketContainer()
    {
        return websocketContainer;
    }

    /**
     * Set PRE-Request Handling function.
     *
     * @param requestFunction the function to handle the request (before upgrade), do whatever you want.
     * Note that if you return true, the request will not process into the default Upgrade flow,
     * false will allow the default Upgrade flow.
     */
    public void setRequestHandling(BiFunction<Request, Response, Boolean> requestFunction)
    {
        this.serverHandler.setFunction(requestFunction);
    }

    public void resetRequestHandling()
    {
        this.serverHandler.setFunction(null);
    }

    public URI getWsUri()
    {
        return wsUri;
    }

    public void start() throws Exception
    {
        this.server.start();

        wsUri = URI.create("ws://localhost:" + this.connector.getLocalPort() + "/");

        LOG.debug("BlockheadServer available on {}", wsUri);
    }

    public void stop() throws Exception
    {
        LOG.debug("Stopping Server");
        this.server.stop();
    }

    public static class BlockheadServerHandler extends AbstractHandler
    {
        private final WebSocketContainerScope container;
        private final WebSocketExtensionFactory extensionFactory;
        private BiFunction<Request, Response, Boolean> requestFunction;
        private LinkedBlockingQueue<CompletableFuture<BlockheadConnection>> futuresQueue;

        public BlockheadServerHandler(WebSocketContainerScope websocketContainer, WebSocketExtensionFactory extensionFactory)
        {
            super();
            this.container = websocketContainer;
            this.extensionFactory = extensionFactory;
            this.futuresQueue = new LinkedBlockingQueue<>();
        }

        public Queue<CompletableFuture<BlockheadConnection>> getWSConnectionFutures()
        {
            return futuresQueue;
        }

        @Override
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            Response baseResponse = (Response)response;
            if (requestFunction != null)
            {
                if (requestFunction.apply(baseRequest, baseResponse))
                {
                    baseRequest.setHandled(true);
                    return;
                }
            }

            CompletableFuture<BlockheadConnection> connFut = this.futuresQueue.poll();

            try
            {
                baseRequest.setHandled(true);

                // default/simplified Upgrade flow
                String key = request.getHeader("Sec-WebSocket-Key");

                if (key == null)
                {
                    throw new IllegalStateException("Missing request header 'Sec-WebSocket-Key'");
                }

                // build response
                response.setHeader("Upgrade", "WebSocket");
                response.addHeader("Connection", "Upgrade");
                response.addHeader("Sec-WebSocket-Accept", AcceptHash.hashKey(key));

                response.setStatus(HttpServletResponse.SC_SWITCHING_PROTOCOLS);

                // Initialize / Negotiate Extensions
                ExtensionStack extensionStack = new ExtensionStack(extensionFactory);

                if (response.containsHeader(SEC_WEBSOCKET_EXTENSIONS))
                {
                    // Use pre-negotiated extension list from response
                    List<ExtensionConfig> extensionConfigs = new ArrayList<>();
                    response.getHeaders(SEC_WEBSOCKET_EXTENSIONS).forEach(
                        (value) -> extensionConfigs.addAll(ExtensionConfig.parseList(value)));
                    extensionStack.negotiate(extensionConfigs);
                }
                else
                {
                    // Use what was given to us
                    Enumeration<String> e = request.getHeaders(SEC_WEBSOCKET_EXTENSIONS);
                    List<ExtensionConfig> extensionConfigs = ExtensionConfig.parseEnum(e);
                    extensionStack.negotiate(extensionConfigs);

                    String negotiatedHeaderValue = ExtensionConfig.toHeaderValue(extensionStack.getNegotiatedExtensions());
                    response.setHeader(SEC_WEBSOCKET_EXTENSIONS, negotiatedHeaderValue);
                }

                WebSocketPolicy policy = this.container.getPolicy().clonePolicy();

                // Get original HTTP connection
                HttpConnection http = (HttpConnection)request.getAttribute("org.eclipse.jetty.server.HttpConnection");

                EndPoint endp = http.getEndPoint();
                Connector connector = http.getConnector();
                Executor executor = connector.getExecutor();
                ByteBufferPool bufferPool = connector.getByteBufferPool();

                // Setup websocket connection
                BlockheadServerConnection wsConnection = new BlockheadServerConnection(
                    policy,
                    bufferPool,
                    extensionStack,
                    connFut,
                    endp,
                    executor);

                if (LOG.isDebugEnabled())
                {
                    LOG.debug("HttpConnection: {}", http);
                    LOG.debug("BlockheadServerConnection: {}", wsConnection);
                }

                wsConnection.setUpgradeRequestHeaders(baseRequest.getHttpFields());
                wsConnection.setUpgradeResponseHeaders(baseResponse.getHttpFields());

                // Tell jetty about the new upgraded connection
                request.setAttribute(HttpConnection.UPGRADE_CONNECTION_ATTRIBUTE, wsConnection);

                if (LOG.isDebugEnabled())
                    LOG.debug("Websocket upgrade {} {}", request.getRequestURI(), wsConnection);
            }
            catch (Throwable cause)
            {
                if (connFut != null)
                    connFut.completeExceptionally(cause);
                LOG.warn(cause);
            }
        }

        public void setFunction(BiFunction<Request, Response, Boolean> function)
        {
            this.requestFunction = function;
        }
    }
}
