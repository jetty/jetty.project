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

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.QueuedThreadPool;

class DefaultHttpClientProvider
{
    public static HttpClient newHttpClient()
    {
        HttpClient client = new HttpClient(new SslContextFactory());
        client.getSslContextFactory().setEndpointIdentificationAlgorithm("HTTPS");

        QueuedThreadPool threadPool = new QueuedThreadPool();
        threadPool.setName("WebSocketClient@" + client.hashCode());
        threadPool.setDaemon(true);
        client.setExecutor(threadPool);

        return client;
    }
}
