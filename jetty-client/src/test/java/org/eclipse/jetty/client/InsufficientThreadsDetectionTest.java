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

package org.eclipse.jetty.client;

import org.eclipse.jetty.client.http.HttpClientTransportOverHTTP;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

public class InsufficientThreadsDetectionTest
{
    @Test
    public void testInsufficientThreads()
    {
        QueuedThreadPool clientThreads = new QueuedThreadPool(1);
        HttpClient httpClient = new HttpClient(new HttpClientTransportOverHTTP(1), null);
        httpClient.setExecutor(clientThreads);
        assertThrows(IllegalStateException.class, () ->
        {
            httpClient.start();
        });
    }

    @Test
    public void testInsufficientThreadsForMultipleHttpClients() throws Exception
    {
        QueuedThreadPool clientThreads = new QueuedThreadPool(3);
        HttpClient httpClient1 = new HttpClient(new HttpClientTransportOverHTTP(1), null);
        httpClient1.setExecutor(clientThreads);
        httpClient1.start();

        assertThrows(IllegalStateException.class, () ->
        {
            // Share the same thread pool with another instance.
            HttpClient httpClient2 = new HttpClient(new HttpClientTransportOverHTTP(1), null);
            httpClient2.setExecutor(clientThreads);
            httpClient2.start();
        });
    }
}
