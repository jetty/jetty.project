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

package org.eclipse.jetty.server;

import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import org.eclipse.jetty.io.ManagedSelector;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.thread.strategy.AdaptiveExecutionStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;

/**
 * HttpServer Tester.
 */
public class ServerConnectorHttpServerTest extends HttpServerTestBase
{
    @BeforeEach
    public void init() throws Exception
    {
        // Run this test with 0 acceptors. Other tests already check the acceptors >0
        initServer(new ServerConnector(_server, 0, 1));
    }

    @Test
    public void testNonBlockingInvocationType() throws Exception
    {
        startServer(new Handler.Processor()
        {
            @Override
            public void doProcess(Request request, Response response, Callback callback) throws Exception
            {
                callback.succeeded();
            }

            @Override
            public InvocationType getInvocationType()
            {
                return InvocationType.NON_BLOCKING;
            }
        });

        try (Socket client = newSocket(_serverURI.getHost(), _serverURI.getPort()))
        {
            OutputStream os = client.getOutputStream();

            String request = """
                GET / HTTP/1.1\r
                Host: localhost\r
                \r
                """;
            os.write(request.getBytes(StandardCharsets.ISO_8859_1));
            os.flush();

            // Read the response.
            String response = readResponseHeader(client);

            assertThat(response, containsString("HTTP/1.1 200 OK"));
        }

        ManagedSelector selector = _connector.getSelectorManager().getBean(ManagedSelector.class);
        AdaptiveExecutionStrategy strategy = selector.getBean(AdaptiveExecutionStrategy.class);
        assertThat(strategy.getPCTasksConsumed(), greaterThan(0L));
        assertThat(strategy.getPECTasksExecuted(), is(0L));
        assertThat(strategy.getPICTasksExecuted(), is(0L));
        assertThat(strategy.getEPCTasksConsumed(), is(0L));
    }

    @Test
    public void testBlockingInvocationType() throws Exception
    {
        startServer(new Handler.Processor()
        {
            @Override
            public void doProcess(Request request, Response response, Callback callback) throws Exception
            {
                callback.succeeded();
            }

            @Override
            public InvocationType getInvocationType()
            {
                return InvocationType.BLOCKING;
            }
        });

        try (Socket client = newSocket(_serverURI.getHost(), _serverURI.getPort()))
        {
            OutputStream os = client.getOutputStream();

            String request = """
                GET / HTTP/1.1\r
                Host: localhost\r
                \r
                """;
            os.write(request.getBytes(StandardCharsets.ISO_8859_1));
            os.flush();

            // Read the response.
            String response = readResponseHeader(client);

            assertThat(response, containsString("HTTP/1.1 200 OK"));
        }

        ManagedSelector selector = _connector.getSelectorManager().getBean(ManagedSelector.class);
        AdaptiveExecutionStrategy strategy = selector.getBean(AdaptiveExecutionStrategy.class);
        assertThat(strategy.getPCTasksConsumed(), is(0L));
        assertThat(strategy.getPECTasksExecuted(), greaterThan(0L));
        assertThat(strategy.getPICTasksExecuted(), is(0L));
        assertThat(strategy.getEPCTasksConsumed(), greaterThanOrEqualTo(0L));
    }
}
