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

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.http.HttpClientTransportOverHTTP;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.component.LifeCycle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

public class ConnectionPoolMaxUsageTest
{
    private Server server;
    private ServerConnector connector;
    private HttpClient httpClient;

    public void start(Handler handler) throws Exception
    {
        server = new Server();
        connector = new ServerConnector(server, 1, 1);
        server.addConnector(connector);
        server.setHandler(handler);
        server.start();

        httpClient = new HttpClient();
        httpClient.start();
    }

    @AfterEach
    public void dispose()
    {
        LifeCycle.stop(httpClient);
        LifeCycle.stop(server);
    }

    @Test
    public void testMaxUsage() throws Exception
    {
        start(new Handler.Abstract()
        {
            @Override
            public boolean process(Request request, Response response, Callback callback)
            {
                response.getHeaders().put(HttpHeader.CONTENT_TYPE, "text/plain");
                Content.Sink.write(response, true, String.valueOf(Request.getRemotePort(request)), callback);
                return true;
            }
        });

        String host = "localhost";
        int port = connector.getLocalPort();
        HttpDestination destination = httpClient.resolveDestination(new Origin("http", host, port, null, HttpClientTransportOverHTTP.HTTP11));
        AbstractConnectionPool connectionPool = (AbstractConnectionPool)destination.getConnectionPool();
        int maxUsage = 3;
        connectionPool.setMaxUsage(maxUsage);

        Set<String> clientPorts = new HashSet<>();
        for (int i = 0; i < maxUsage; ++i)
        {
            ContentResponse response = httpClient.newRequest(host, port)
                .timeout(5, TimeUnit.SECONDS)
                .send();
            assertEquals(HttpStatus.OK_200, response.getStatus());
            int expected = i == maxUsage - 1 ? 0 : 1;
            assertEquals(expected, connectionPool.getConnectionCount());
            clientPorts.add(response.getContentAsString());
        }
        assertEquals(1, clientPorts.size());

        // Make one more request, it must open a new connection.
        ContentResponse response = httpClient.newRequest(host, port)
            .timeout(5, TimeUnit.SECONDS)
            .send();
        assertEquals(HttpStatus.OK_200, response.getStatus());
        assertEquals(1, connectionPool.getConnectionCount());
        assertNotEquals(clientPorts.iterator().next(), response.getContentAsString());
    }

    @Test
    public void testMaxUsageSetToSmallerValue() throws Exception
    {
        start(new Handler.Abstract()
        {
            @Override
            public boolean process(Request request, Response response, Callback callback)
            {
                response.getHeaders().put(HttpHeader.CONTENT_TYPE, "text/plain");
                Content.Sink.write(response, true, String.valueOf(Request.getRemotePort(request)), callback);
                return true;
            }
        });

        String host = "localhost";
        int port = connector.getLocalPort();
        HttpDestination destination = httpClient.resolveDestination(new Origin("http", host, port, null, HttpClientTransportOverHTTP.HTTP11));
        AbstractConnectionPool connectionPool = (AbstractConnectionPool)destination.getConnectionPool();
        int maxUsage = 3;
        connectionPool.setMaxUsage(maxUsage);

        // Make a number of requests smaller than maxUsage.
        Set<String> clientPorts = new HashSet<>();
        for (int i = 0; i < maxUsage - 1; ++i)
        {
            ContentResponse response = httpClient.newRequest(host, port)
                .timeout(5, TimeUnit.SECONDS)
                .send();
            assertEquals(HttpStatus.OK_200, response.getStatus());
            assertEquals(1, connectionPool.getConnectionCount());
            clientPorts.add(response.getContentAsString());
        }
        assertEquals(1, clientPorts.size());

        // Set maxUsage to a smaller value.
        connectionPool.setMaxUsage(1);

        // Make one more request, it must open a new connection.
        ContentResponse response = httpClient.newRequest(host, port)
            .timeout(5, TimeUnit.SECONDS)
            .send();
        assertEquals(HttpStatus.OK_200, response.getStatus());
        assertEquals(0, connectionPool.getConnectionCount());
        assertNotEquals(clientPorts.iterator().next(), response.getContentAsString());
    }
}
