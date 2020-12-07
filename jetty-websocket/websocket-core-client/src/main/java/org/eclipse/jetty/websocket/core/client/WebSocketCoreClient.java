//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.websocket.core.client;

import java.io.IOException;
import java.net.URI;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.util.DecoratedObjectFactory;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.websocket.core.CoreSession;
import org.eclipse.jetty.websocket.core.ExtensionConfig;
import org.eclipse.jetty.websocket.core.FrameHandler;
import org.eclipse.jetty.websocket.core.WebSocketComponents;
import org.eclipse.jetty.websocket.core.WebSocketExtensionRegistry;
import org.eclipse.jetty.websocket.core.client.internal.HttpClientProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class WebSocketCoreClient extends ContainerLifeCycle
{
    public static final String WEBSOCKET_CORECLIENT_ATTRIBUTE = WebSocketCoreClient.class.getName();

    private static final Logger LOG = LoggerFactory.getLogger(WebSocketCoreClient.class);
    private final HttpClient httpClient;
    private final WebSocketComponents components;

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

    public CompletableFuture<CoreSession> connect(FrameHandler frameHandler, URI wsUri) throws IOException
    {
        CoreClientUpgradeRequest request = CoreClientUpgradeRequest.from(this, wsUri, frameHandler);
        return connect(request);
    }

    public CompletableFuture<CoreSession> connect(CoreClientUpgradeRequest request) throws IOException
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

        for (Request.Listener l : getBeans(Request.Listener.class))
        {
            request.listener(l);
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
