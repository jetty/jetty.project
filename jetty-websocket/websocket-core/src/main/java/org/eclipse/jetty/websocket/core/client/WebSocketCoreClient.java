//
//  ========================================================================
//  Copyright (c) 1995-2019 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.core.client;

import java.io.IOException;
import java.net.URI;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.util.DecoratedObjectFactory;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.core.ExtensionConfig;
import org.eclipse.jetty.websocket.core.FrameHandler;
import org.eclipse.jetty.websocket.core.WebSocketComponents;
import org.eclipse.jetty.websocket.core.WebSocketExtensionRegistry;

public class WebSocketCoreClient extends ContainerLifeCycle
{
    public static final String WEBSOCKET_CORECLIENT_ATTRIBUTE = WebSocketCoreClient.class.getName();

    private static final Logger LOG = Log.getLogger(WebSocketCoreClient.class);
    private final HttpClient httpClient;
    private WebSocketComponents components;

    // TODO: Things to consider for inclusion in this class (or removal if they can be set elsewhere, like HttpClient)
    // - AsyncWrite Idle Timeout
    // - Bind Address
    // - SslContextFactory setup
    // - Connect Timeout
    // - Cookie Store

    public WebSocketCoreClient()
    {
        this(null, new WebSocketComponents());
    }

    public WebSocketCoreClient(WebSocketComponents webSocketComponents)
    {
        this(null, webSocketComponents);
    }

    public WebSocketCoreClient(HttpClient httpClient, WebSocketComponents webSocketComponents)
    {
        if (httpClient == null)
            httpClient = Objects.requireNonNull(HttpClientProvider.get());

        this.httpClient = httpClient;
        this.components = webSocketComponents;
        addBean(httpClient);
    }

    public CompletableFuture<FrameHandler.CoreSession> connect(FrameHandler frameHandler, URI wsUri) throws IOException
    {
        ClientUpgradeRequest request = ClientUpgradeRequest.from(this, wsUri, frameHandler);
        return connect(request);
    }

    public CompletableFuture<FrameHandler.CoreSession> connect(ClientUpgradeRequest request) throws IOException
    {
        if (!isStarted())
            throw new IllegalStateException(WebSocketCoreClient.class.getSimpleName() + "@" + this.hashCode() + " is not started");

        // Validate Requested Extensions
        for (ExtensionConfig reqExt : request.getExtensions())
        {
            if (!components.getExtensionRegistry().isAvailable(reqExt.getName()))
            {
                throw new IllegalArgumentException("Requested extension [" + reqExt.getName() + "] is not installed");
            }
        }

        if (LOG.isDebugEnabled())
            LOG.debug("connect to websocket {}", request.getURI());

        return request.sendAsync();
    }

    public WebSocketExtensionRegistry getExtensionRegistry()
    {
        return components.getExtensionRegistry();
    }

    public HttpClient getHttpClient()
    {
        return httpClient;
    }

    public DecoratedObjectFactory getObjectFactory()
    {
        return components.getObjectFactory();
    }

    public WebSocketComponents getWebSocketComponents()
    {
        return components;
    }
}
