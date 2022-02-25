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
        HttpClient httpClient = new HttpClient(new HttpClientTransportOverHTTP(1));
        httpClient.setExecutor(clientThreads);
        assertThrows(IllegalStateException.class, httpClient::start);
    }

    @Test
    public void testInsufficientThreadsForMultipleHttpClients() throws Exception
    {
        QueuedThreadPool clientThreads = new QueuedThreadPool(3);
        HttpClient httpClient1 = new HttpClient(new HttpClientTransportOverHTTP(1));
        httpClient1.setExecutor(clientThreads);
        httpClient1.start();

        assertThrows(IllegalStateException.class, () ->
        {
            // Share the same thread pool with another instance.
            HttpClient httpClient2 = new HttpClient(new HttpClientTransportOverHTTP(1));
            httpClient2.setExecutor(clientThreads);
            httpClient2.start();
        });
    }
}
