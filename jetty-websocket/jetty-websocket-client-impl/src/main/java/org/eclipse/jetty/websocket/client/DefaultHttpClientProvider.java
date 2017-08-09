//
//  ========================================================================
//  Copyright (c) 1995-2017 Mort Bay Consulting Pty. Ltd.
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
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.websocket.common.scopes.WebSocketContainerScope;

class DefaultHttpClientProvider
{
    public static HttpClient newHttpClient(WebSocketContainerScope scope)
    {
        SslContextFactory sslContextFactory = null;
        Executor executor = null;
        
        if (scope != null)
        {
            sslContextFactory = scope.getSslContextFactory();
            executor = scope.getExecutor();
        }
        
        if (sslContextFactory == null)
        {
            sslContextFactory = new SslContextFactory();
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
        return client;
    }
}
