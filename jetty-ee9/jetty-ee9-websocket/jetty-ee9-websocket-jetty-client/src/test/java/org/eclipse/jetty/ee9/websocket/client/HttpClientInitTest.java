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

package org.eclipse.jetty.ee9.websocket.client;

import java.util.concurrent.Executor;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.hamcrest.core.Is.is;

public class HttpClientInitTest
{
    @Test
    public void testDefaultInit() throws Exception
    {
        WebSocketClient client = new WebSocketClient();
        try
        {
            client.start();
            HttpClient httpClient = client.getHttpClient();
            assertThat("HttpClient exists", httpClient, notNullValue());
            assertThat("HttpClient is started", httpClient.isStarted(), is(true));
            Executor executor = httpClient.getExecutor();
            assertThat("Executor exists", executor, notNullValue());
            assertThat("Executor instanceof", executor, instanceOf(QueuedThreadPool.class));
            QueuedThreadPool threadPool = (QueuedThreadPool)executor;
            assertThat("QueuedThreadPool.name", threadPool.getName(), startsWith("WebSocket@"));
        }
        finally
        {
            client.stop();
        }
    }

    @Test
    public void testManualInit() throws Exception
    {
        HttpClient http = new HttpClient();
        {
            QueuedThreadPool threadPool = new QueuedThreadPool();
            threadPool.setName("ManualWSClient@" + http.hashCode());
            http.setExecutor(threadPool);
            http.setConnectTimeout(7777);
        }

        WebSocketClient client = new WebSocketClient(http);
        client.addBean(http);
        try
        {
            client.start();
            HttpClient httpClient = client.getHttpClient();
            assertThat("HttpClient exists", httpClient, notNullValue());
            assertThat("HttpClient is started", httpClient.isStarted(), is(true));
            assertThat("HttpClient.connectTimeout", httpClient.getConnectTimeout(), is(7777L));
            Executor executor = httpClient.getExecutor();
            assertThat("Executor exists", executor, notNullValue());
            assertThat("Executor instanceof", executor, instanceOf(QueuedThreadPool.class));
            QueuedThreadPool threadPool = (QueuedThreadPool)executor;
            assertThat("QueuedThreadPool.name", threadPool.getName(), startsWith("ManualWSClient@"));
        }
        finally
        {
            client.stop();
        }
    }
}
