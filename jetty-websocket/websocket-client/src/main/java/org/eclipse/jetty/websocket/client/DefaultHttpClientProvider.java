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

package org.eclipse.jetty.websocket.client;

import java.util.concurrent.Executor;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.MappedByteBufferPool;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.websocket.common.scopes.WebSocketContainerScope;

class DefaultHttpClientProvider
{
    public static HttpClient newHttpClient(WebSocketContainerScope scope)
    {
        SslContextFactory sslContextFactory = null;
        Executor executor = null;
        ByteBufferPool bufferPool = null;

        if (scope != null)
        {
            sslContextFactory = scope.getSslContextFactory();
            executor = scope.getExecutor();
            bufferPool = scope.getBufferPool();
        }

        if (sslContextFactory == null)
        {
            sslContextFactory = new SslContextFactory.Client();
            sslContextFactory.setTrustAll(false);
            sslContextFactory.setEndpointIdentificationAlgorithm("HTTPS");
        }

        HttpClient client = new HttpClient(sslContextFactory);
        if (executor == null)
        {
            QueuedThreadPool threadPool = new QueuedThreadPool();
            String name = "WebSocketClient@" + client.hashCode();
            threadPool.setName(name);
            threadPool.setDaemon(true);
            executor = threadPool;
        }
        client.setExecutor(executor);

        if (bufferPool == null)
        {
            bufferPool = new MappedByteBufferPool();
        }
        client.setByteBufferPool(bufferPool);

        return client;
    }
}
