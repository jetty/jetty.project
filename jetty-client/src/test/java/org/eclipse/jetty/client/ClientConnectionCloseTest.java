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

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.servlet.ServletInputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.http.HttpConnectionOverHTTP;
import org.eclipse.jetty.client.http.HttpDestinationOverHTTP;
import org.eclipse.jetty.client.util.DeferredContentProvider;
import org.eclipse.jetty.client.util.StringContentProvider;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpHeaderValue;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ClientConnectionCloseTest extends AbstractHttpClientServerTest
{
    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testClientConnectionCloseServerConnectionCloseClientClosesAfterExchange(Scenario scenario) throws Exception
    {
        byte[] data = new byte[128 * 1024];
        start(scenario, new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                baseRequest.setHandled(true);

                ServletInputStream input = request.getInputStream();
                while (true)
                {
                    int read = input.read();
                    if (read < 0)
                        break;
                }

                response.setContentLength(data.length);
                response.getOutputStream().write(data);

                try
                {
                    // Delay the server from sending the TCP FIN.
                    Thread.sleep(1000);
                }
                catch (InterruptedException x)
                {
                    throw new InterruptedIOException();
                }
            }
        });

        String host = "localhost";
        int port = connector.getLocalPort();

        HttpDestinationOverHTTP destination = (HttpDestinationOverHTTP)client.getDestination(scenario.getScheme(), host, port);
        DuplexConnectionPool connectionPool = (DuplexConnectionPool)destination.getConnectionPool();

        ContentResponse response = client.newRequest(host, port)
            .scheme(scenario.getScheme())
            .header(HttpHeader.CONNECTION, HttpHeaderValue.CLOSE.asString())
            .content(new StringContentProvider("0"))
            .onRequestSuccess(request ->
            {
                HttpConnectionOverHTTP connection = (HttpConnectionOverHTTP)connectionPool.getActiveConnections().iterator().next();
                assertFalse(connection.getEndPoint().isOutputShutdown());
            })
            .send();

        assertEquals(HttpStatus.OK_200, response.getStatus());
        assertArrayEquals(data, response.getContent());
        assertEquals(0, connectionPool.getConnectionCount());
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testClientConnectionCloseServerDoesNotRespondClientIdleTimeout(Scenario scenario) throws Exception
    {
        start(scenario, new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response)
            {
                baseRequest.setHandled(true);
                request.startAsync();
                // Do not respond.
            }
        });

        String host = "localhost";
        int port = connector.getLocalPort();

        HttpDestinationOverHTTP destination = (HttpDestinationOverHTTP)client.getDestination(scenario.getScheme(), host, port);
        DuplexConnectionPool connectionPool = (DuplexConnectionPool)destination.getConnectionPool();

        CountDownLatch resultLatch = new CountDownLatch(1);
        long idleTimeout = 1000;
        client.newRequest(host, port)
            .scheme(scenario.getScheme())
            .header(HttpHeader.CONNECTION, HttpHeaderValue.CLOSE.asString())
            .idleTimeout(idleTimeout, TimeUnit.MILLISECONDS)
            .onRequestSuccess(request ->
            {
                HttpConnectionOverHTTP connection = (HttpConnectionOverHTTP)connectionPool.getActiveConnections().iterator().next();
                assertFalse(connection.getEndPoint().isOutputShutdown());
            })
            .send(result ->
            {
                if (result.isFailed())
                    resultLatch.countDown();
            });

        assertTrue(resultLatch.await(2 * idleTimeout, TimeUnit.MILLISECONDS));
        assertEquals(0, connectionPool.getConnectionCount());
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testClientConnectionCloseServerPartialResponseClientIdleTimeout(Scenario scenario) throws Exception
    {
        long idleTimeout = 1000;
        start(scenario, new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                baseRequest.setHandled(true);

                ServletInputStream input = request.getInputStream();
                while (true)
                {
                    int read = input.read();
                    if (read < 0)
                        break;
                }

                response.getOutputStream().print("Hello");
                response.flushBuffer();

                try
                {
                    Thread.sleep(2 * idleTimeout);
                }
                catch (InterruptedException x)
                {
                    throw new InterruptedIOException();
                }
            }
        });

        String host = "localhost";
        int port = connector.getLocalPort();

        HttpDestinationOverHTTP destination = (HttpDestinationOverHTTP)client.getDestination(scenario.getScheme(), host, port);
        DuplexConnectionPool connectionPool = (DuplexConnectionPool)destination.getConnectionPool();

        DeferredContentProvider content = new DeferredContentProvider(ByteBuffer.allocate(8));
        CountDownLatch resultLatch = new CountDownLatch(1);
        client.newRequest(host, port)
            .scheme(scenario.getScheme())
            .header(HttpHeader.CONNECTION, HttpHeaderValue.CLOSE.asString())
            .content(content)
            .idleTimeout(idleTimeout, TimeUnit.MILLISECONDS)
            .onRequestSuccess(request ->
            {
                HttpConnectionOverHTTP connection = (HttpConnectionOverHTTP)connectionPool.getActiveConnections().iterator().next();
                assertFalse(connection.getEndPoint().isOutputShutdown());
            })
            .send(result ->
            {
                if (result.isFailed())
                    resultLatch.countDown();
            });
        content.offer(ByteBuffer.allocate(8));
        content.close();

        assertTrue(resultLatch.await(2 * idleTimeout, TimeUnit.MILLISECONDS));
        assertEquals(0, connectionPool.getConnectionCount());
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testClientConnectionCloseServerNoConnectionCloseClientCloses(Scenario scenario) throws Exception
    {
        start(scenario, new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                baseRequest.setHandled(true);
                response.setContentLength(0);
                response.flushBuffer();

                try
                {
                    // Delay the server from sending the TCP FIN.
                    Thread.sleep(1000);
                }
                catch (InterruptedException x)
                {
                    throw new InterruptedIOException();
                }
            }
        });

        String host = "localhost";
        int port = connector.getLocalPort();

        HttpDestinationOverHTTP destination = (HttpDestinationOverHTTP)client.getDestination(scenario.getScheme(), host, port);
        DuplexConnectionPool connectionPool = (DuplexConnectionPool)destination.getConnectionPool();

        ContentResponse response = client.newRequest(host, port)
            .scheme(scenario.getScheme())
            .header(HttpHeader.CONNECTION, HttpHeaderValue.CLOSE.asString())
            .onRequestSuccess(request ->
            {
                HttpConnectionOverHTTP connection = (HttpConnectionOverHTTP)connectionPool.getActiveConnections().iterator().next();
                assertFalse(connection.getEndPoint().isOutputShutdown());
            })
            .onResponseHeaders(r -> r.getHeaders().remove(HttpHeader.CONNECTION))
            .send();

        assertEquals(HttpStatus.OK_200, response.getStatus());
        assertEquals(0, connectionPool.getConnectionCount());
    }
}
