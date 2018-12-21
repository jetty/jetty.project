//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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
import java.util.concurrent.CompletableFuture;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.util.DecoratedObjectFactory;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.ShutdownThread;
import org.eclipse.jetty.websocket.core.ExtensionConfig;
import org.eclipse.jetty.websocket.core.FrameHandler;
import org.eclipse.jetty.websocket.core.WebSocketExtensionRegistry;

public class WebSocketCoreClient extends ContainerLifeCycle implements FrameHandler.Customizer
{

    private static final Logger LOG = Log.getLogger(WebSocketCoreClient.class);
    private final HttpClient httpClient;
    private WebSocketExtensionRegistry extensionRegistry;
    private DecoratedObjectFactory objectFactory;
    private final FrameHandler.Customizer customizer;

    // TODO: Things to consider for inclusion in this class (or removal if they can be set elsewhere, like HttpClient)
    // - AsyncWrite Idle Timeout
    // - Bind Address
    // - SslContextFactory setup
    // - Connect Timeout
    // - Cookie Store

    public WebSocketCoreClient()
    {
        this(null,null);
    }

    public WebSocketCoreClient(HttpClient httpClient)
    {
        this(httpClient, null);
    }

    public WebSocketCoreClient(HttpClient httpClient, FrameHandler.Customizer customizer)
    {
        if (httpClient==null)
        {
            httpClient = new HttpClient(new SslContextFactory());
            httpClient.getSslContextFactory().setEndpointIdentificationAlgorithm("HTTPS");
            httpClient.setName(String.format("%s@%x",getClass().getSimpleName(),hashCode()));
        }
        this.httpClient = httpClient;
        this.extensionRegistry = new WebSocketExtensionRegistry();
        this.objectFactory = new DecoratedObjectFactory();
        this.customizer = customizer;
        addBean(httpClient);
    }

    @Override
    public void customize(FrameHandler.CoreSession session)
    {
        if (customizer != null)
            customizer.customize(session);
    }

    public CompletableFuture<FrameHandler.CoreSession> connect(FrameHandler frameHandler, URI wsUri) throws IOException
    {
        UpgradeRequest request = UpgradeRequest.from(this, wsUri, frameHandler);
        return connect(request);
    }

    public CompletableFuture<FrameHandler.CoreSession> connect(UpgradeRequest request) throws IOException
    {
        if (!isStarted())
        {
            throw new IllegalStateException(WebSocketCoreClient.class.getSimpleName() + "@" + this.hashCode() + " is not started");
        }

        // TODO: add HttpClient delayed/on-demand start - See Issue #1516

        // Validate Requested Extensions
        for (ExtensionConfig reqExt : request.getExtensions())
        {
            if (!extensionRegistry.isAvailable(reqExt.getName()))
            {
                throw new IllegalArgumentException("Requested extension [" + reqExt.getName() + "] is not installed");
            }
        }

        if (LOG.isDebugEnabled())
            LOG.debug("connect to websocket {}", request.getURI());

        init();

        return request.sendAsync();
    }

    // TODO: review need for this.
    private synchronized void init() throws IOException
    {
        if (!ShutdownThread.isRegistered(this))
        {
            ShutdownThread.register(this);
        }
    }

    public WebSocketExtensionRegistry getExtensionRegistry()
    {
        return extensionRegistry;
    }

    public HttpClient getHttpClient()
    {
        return httpClient;
    }

    public DecoratedObjectFactory getObjectFactory()
    {
        return objectFactory;
    }
}
