//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
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
import org.eclipse.jetty.client.Request;
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
    private ClassLoader classLoader;

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
        if (httpClient.getExecutor() == null)
            httpClient.setExecutor(webSocketComponents.getExecutor());

        this.classLoader = Thread.currentThread().getContextClassLoader();
        this.httpClient = httpClient;
        this.components = webSocketComponents;
        addBean(httpClient);
        addBean(webSocketComponents);
    }

    public ClassLoader getClassLoader()
    {
        return classLoader;
    }

    public void setClassLoader(ClassLoader classLoader)
    {
        this.classLoader = Objects.requireNonNull(classLoader);
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
