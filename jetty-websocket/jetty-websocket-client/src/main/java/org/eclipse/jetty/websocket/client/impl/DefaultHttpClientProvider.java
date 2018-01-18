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

package org.eclipse.jetty.websocket.client.impl;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.QueuedThreadPool;

class DefaultHttpClientProvider
{
    public static HttpClient newHttpClient()
    {
        SslContextFactory sslContextFactory = new SslContextFactory();
        HttpClient client = new HttpClient(sslContextFactory);
        QueuedThreadPool threadPool = new QueuedThreadPool();
        String name = "WebSocketClient@" + client.hashCode();
        threadPool.setName(name);
        threadPool.setDaemon(true);
        client.setExecutor(threadPool);
        return client;
    }
}
