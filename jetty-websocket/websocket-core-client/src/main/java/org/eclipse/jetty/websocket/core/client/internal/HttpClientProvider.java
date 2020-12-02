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

package org.eclipse.jetty.websocket.core.client.internal;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.util.thread.QueuedThreadPool;

public interface HttpClientProvider
{
    static HttpClient get()
    {
        HttpClientProvider xmlProvider = new XmlHttpClientProvider();
        HttpClient client = xmlProvider.newHttpClient();
        if (client != null)
            return client;

        return HttpClientProvider.newDefaultHttpClient();
    }

    private static HttpClient newDefaultHttpClient()
    {
        HttpClient client = new HttpClient();
        QueuedThreadPool threadPool = new QueuedThreadPool();
        threadPool.setName("WebSocketClient@" + client.hashCode());
        client.setExecutor(threadPool);
        return client;
    }

    default HttpClient newHttpClient()
    {
        return newDefaultHttpClient();
    }
}
