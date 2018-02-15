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
import org.eclipse.jetty.client.HttpResponse;
import org.eclipse.jetty.util.DecoratedObjectFactory;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.ShutdownThread;
import org.eclipse.jetty.websocket.core.FrameHandler;
import org.eclipse.jetty.websocket.core.WebSocketBehavior;
import org.eclipse.jetty.websocket.core.WebSocketPolicy;
import org.eclipse.jetty.websocket.core.extensions.ExtensionConfig;
import org.eclipse.jetty.websocket.core.extensions.WebSocketExtensionRegistry;

public class WebSocketCoreClient extends ContainerLifeCycle
{

    private static final Logger LOG = Log.getLogger(WebSocketCoreClient.class);
    private HttpClient httpClient;
    private WebSocketPolicy policy;
    private WebSocketExtensionRegistry extensionRegistry;
    private DecoratedObjectFactory objectFactory;

    // TODO: Things to consider for inclusion in this class (or removal if they can be set elsewhere, like HttpClient)
    // - AsyncWrite Idle Timeout
    // - Bind Address
    // - SslContextFactory setup
    // - Connect Timeout
    // - Cookie Store

    public WebSocketCoreClient()
    {
        this(new HttpClient(new SslContextFactory()));
        this.httpClient.setName("WebSocketCoreClient");
        // Internally created, let websocket client's lifecycle manage it.
        this.addManaged(httpClient);
    }

    public WebSocketCoreClient(HttpClient httpClient)
    {
        this(WebSocketPolicy.newClientPolicy(), httpClient);
    }

    public WebSocketCoreClient(WebSocketPolicy policy)
    {
        this(policy, null);
    }

    public WebSocketCoreClient(WebSocketPolicy policy, HttpClient httpClient)
    {
        this.httpClient = httpClient == null ? new HttpClient() : httpClient;
        this.policy = policy.clonePolicyAs(WebSocketBehavior.CLIENT);
        this.extensionRegistry = new WebSocketExtensionRegistry();
        this.objectFactory = new DecoratedObjectFactory();
    }

    public CompletableFuture<FrameHandler.Channel> connect(FrameHandler frameHandler, URI wsUri) throws IOException
    {
        WebSocketCoreClientUpgradeRequest request = new WebSocketCoreClientUpgradeRequest(this, wsUri) {
            @Override
            public FrameHandler getFrameHandler(WebSocketCoreClient coreClient, WebSocketPolicy upgradePolicy, HttpResponse response)
            {
                return frameHandler;
            }
        };
        return connect(request);
    }

    public CompletableFuture<FrameHandler.Channel> connect(WebSocketCoreClientUpgradeRequest request) throws IOException
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

    public WebSocketPolicy getPolicy()
    {
        return policy;
    }
}
