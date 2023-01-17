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

package org.eclipse.jetty.ee9.demos;

import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.transport.HttpClientTransportOverHTTP;
import org.eclipse.jetty.io.ClientConnector;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

public abstract class AbstractEmbeddedTest
{
    public HttpClient client;

    @BeforeEach
    public void startClient() throws Exception
    {
        SslContextFactory.Client sslContextFactory = new SslContextFactory.Client();
        sslContextFactory.setTrustAll(true);

        ClientConnector clientConnector = new ClientConnector();
        clientConnector.setSelectors(1);
        clientConnector.setSslContextFactory(sslContextFactory);

        QueuedThreadPool clientThreads = new QueuedThreadPool();
        clientThreads.setName("client");

        clientConnector.setExecutor(clientThreads);

        client = new HttpClient(new HttpClientTransportOverHTTP(clientConnector));
        client.start();
    }

    @AfterEach
    public void stopClient() throws Exception
    {
        client.stop();
    }

    protected void dumpResponseHeaders(ContentResponse response)
    {
        System.out.printf("%s %s %s%n", response.getVersion(), response.getStatus(), response.getReason());
        System.out.println(response.getHeaders());
    }
}
