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

import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.http.HttpConnectionOverHTTP;
import org.eclipse.jetty.client.util.AsyncRequestContent;
import org.eclipse.jetty.client.util.StringRequestContent;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpHeaderValue;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Blocking;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.thread.Invocable.InvocationType;
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
        start(scenario, new Handler.Processor(InvocationType.BLOCKING)
        {
            @Override
            public void process(Request request, Response response, Callback callback) throws Exception
            {
                Content.Source.consumeAll(request);

                response.getHeaders().putLongField(HttpHeader.CONTENT_LENGTH, data.length);
                response.write(true, callback, ByteBuffer.wrap(data));

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

        var request = client.newRequest(host, port)
            .scheme(scenario.getScheme())
            .headers(headers -> headers.put(HttpHeader.CONNECTION, HttpHeaderValue.CLOSE))
            .body(new StringRequestContent("0"))
            .onRequestSuccess(r ->
            {
                HttpDestination destination = (HttpDestination)client.resolveDestination(r);
                DuplexConnectionPool connectionPool = (DuplexConnectionPool)destination.getConnectionPool();
                HttpConnectionOverHTTP connection = (HttpConnectionOverHTTP)connectionPool.getActiveConnections().iterator().next();
                assertFalse(connection.getEndPoint().isOutputShutdown());
            });
        ContentResponse response = request.send();

        assertEquals(HttpStatus.OK_200, response.getStatus());
        assertArrayEquals(data, response.getContent());
        HttpDestination destination = (HttpDestination)client.resolveDestination(request);
        DuplexConnectionPool connectionPool = (DuplexConnectionPool)destination.getConnectionPool();
        assertEquals(0, connectionPool.getConnectionCount());
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testClientConnectionCloseServerDoesNotRespondClientIdleTimeout(Scenario scenario) throws Exception
    {
        start(scenario, new Handler.Processor()
        {
            @Override
            public void process(Request request, Response response, Callback callback)
            {
                // Do not respond.
            }
        });

        String host = "localhost";
        int port = connector.getLocalPort();

        CountDownLatch resultLatch = new CountDownLatch(1);
        long idleTimeout = 1000;
        var request = client.newRequest(host, port)
            .scheme(scenario.getScheme())
            .headers(headers -> headers.put(HttpHeader.CONNECTION, HttpHeaderValue.CLOSE))
            .idleTimeout(idleTimeout, TimeUnit.MILLISECONDS)
            .onRequestSuccess(r ->
            {
                HttpDestination destination = (HttpDestination)client.resolveDestination(r);
                DuplexConnectionPool connectionPool = (DuplexConnectionPool)destination.getConnectionPool();
                HttpConnectionOverHTTP connection = (HttpConnectionOverHTTP)connectionPool.getActiveConnections().iterator().next();
                assertFalse(connection.getEndPoint().isOutputShutdown());
            });
        request.send(result ->
        {
            if (result.isFailed())
                resultLatch.countDown();
        });

        assertTrue(resultLatch.await(2 * idleTimeout, TimeUnit.MILLISECONDS));
        HttpDestination destination = (HttpDestination)client.resolveDestination(request);
        DuplexConnectionPool connectionPool = (DuplexConnectionPool)destination.getConnectionPool();
        assertEquals(0, connectionPool.getConnectionCount());
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testClientConnectionCloseServerPartialResponseClientIdleTimeout(Scenario scenario) throws Exception
    {
        long idleTimeout = 1000;
        start(scenario, new Handler.Processor(InvocationType.BLOCKING)
        {
            @Override
            public void process(Request request, Response response, Callback callback) throws Exception
            {
                Content.Source.consumeAll(request);

                try (Blocking.Callback block = Blocking.callback())
                {
                    response.write(false, block, "Hello");
                    block.block();
                }

                try
                {
                    Thread.sleep(2 * idleTimeout);
                }
                catch (InterruptedException x)
                {
                    throw new InterruptedIOException();
                }

                callback.succeeded();
            }
        });

        String host = "localhost";
        int port = connector.getLocalPort();

        AsyncRequestContent content = new AsyncRequestContent(ByteBuffer.allocate(8));
        CountDownLatch resultLatch = new CountDownLatch(1);
        var request = client.newRequest(host, port)
            .scheme(scenario.getScheme())
            .headers(headers -> headers.put(HttpHeader.CONNECTION, HttpHeaderValue.CLOSE))
            .body(content)
            .idleTimeout(idleTimeout, TimeUnit.MILLISECONDS)
            .onRequestSuccess(r ->
            {
                HttpDestination destination = (HttpDestination)client.resolveDestination(r);
                DuplexConnectionPool connectionPool = (DuplexConnectionPool)destination.getConnectionPool();
                HttpConnectionOverHTTP connection = (HttpConnectionOverHTTP)connectionPool.getActiveConnections().iterator().next();
                assertFalse(connection.getEndPoint().isOutputShutdown());
            });
        request.send(result ->
        {
            if (result.isFailed())
                resultLatch.countDown();
        });
        content.write(ByteBuffer.allocate(8), Callback.NOOP);
        content.close();

        assertTrue(resultLatch.await(2 * idleTimeout, TimeUnit.MILLISECONDS));
        HttpDestination destination = (HttpDestination)client.resolveDestination(request);
        DuplexConnectionPool connectionPool = (DuplexConnectionPool)destination.getConnectionPool();
        assertEquals(0, connectionPool.getConnectionCount());
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testClientConnectionCloseServerNoConnectionCloseClientCloses(Scenario scenario) throws Exception
    {
        start(scenario, new Handler.Processor(InvocationType.BLOCKING)
        {
            @Override
            public void process(Request request, Response response, Callback callback) throws Exception
            {
                response.getHeaders().putLongField(HttpHeader.CONTENT_LENGTH, 0);
                try (Blocking.Callback block = Blocking.callback())
                {
                    response.write(false, block);
                    block.block();
                }

                try
                {
                    // Delay the server from sending the TCP FIN.
                    Thread.sleep(1000);
                }
                catch (InterruptedException x)
                {
                    throw new InterruptedIOException();
                }
                callback.succeeded();
            }
        });

        String host = "localhost";
        int port = connector.getLocalPort();

        var request = client.newRequest(host, port)
            .scheme(scenario.getScheme())
            .headers(headers -> headers.put(HttpHeader.CONNECTION, HttpHeaderValue.CLOSE))
            .onRequestSuccess(r ->
            {
                HttpDestination destination = (HttpDestination)client.resolveDestination(r);
                DuplexConnectionPool connectionPool = (DuplexConnectionPool)destination.getConnectionPool();
                HttpConnectionOverHTTP connection = (HttpConnectionOverHTTP)connectionPool.getActiveConnections().iterator().next();
                assertFalse(connection.getEndPoint().isOutputShutdown());
            })
            .onResponseHeaders(r -> ((HttpResponse)r).headers(headers -> headers.remove(HttpHeader.CONNECTION)));
        ContentResponse response = request.send();

        assertEquals(HttpStatus.OK_200, response.getStatus());
        HttpDestination destination = (HttpDestination)client.resolveDestination(request);
        DuplexConnectionPool connectionPool = (DuplexConnectionPool)destination.getConnectionPool();
        assertEquals(0, connectionPool.getConnectionCount());
    }
}
