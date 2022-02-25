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

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Collection;
import java.util.Queue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.client.api.Connection;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.client.api.Result;
import org.eclipse.jetty.client.util.ByteBufferRequestContent;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.logging.StacklessLogging;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class HttpConnectionLifecycleTest extends AbstractHttpClientServerTest
{
    private static final Logger LOG = LoggerFactory.getLogger(HttpConnectionLifecycleTest.class);

    @Override
    public HttpClient newHttpClient(HttpClientTransport transport)
    {
        HttpClient client = super.newHttpClient(transport);
        client.setStrictEventOrdering(false);
        return client;
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testSuccessfulRequestReturnsConnection(Scenario scenario) throws Exception
    {
        start(scenario, new EmptyServerHandler());

        String host = "localhost";
        int port = connector.getLocalPort();
        CountDownLatch headersLatch = new CountDownLatch(1);
        CountDownLatch successLatch = new CountDownLatch(3);
        Request request = client.newRequest(host, port).scheme(scenario.getScheme());
        HttpDestination destination = (HttpDestination)client.resolveDestination(request);
        DuplexConnectionPool connectionPool = (DuplexConnectionPool)destination.getConnectionPool();

        assertEquals(0, connectionPool.getIdleConnections().size());
        assertEquals(0, connectionPool.getActiveConnections().size());

        request.onRequestSuccess(r -> successLatch.countDown())
            .onResponseHeaders(response ->
            {
                assertEquals(0, ((DuplexConnectionPool)destination.getConnectionPool()).getIdleConnections().size());
                assertEquals(1, ((DuplexConnectionPool)destination.getConnectionPool()).getActiveConnections().size());
                headersLatch.countDown();
            })
            .send(new Response.Listener.Adapter()
            {
                @Override
                public void onSuccess(Response response)
                {
                    successLatch.countDown();
                }

                @Override
                public void onComplete(Result result)
                {
                    assertFalse(result.isFailed());
                    successLatch.countDown();
                }
            });

        assertTrue(headersLatch.await(30, TimeUnit.SECONDS));
        assertTrue(successLatch.await(30, TimeUnit.SECONDS));

        assertEquals(1, ((DuplexConnectionPool)destination.getConnectionPool()).getIdleConnections().size());
        assertEquals(0, ((DuplexConnectionPool)destination.getConnectionPool()).getActiveConnections().size());
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testFailedRequestRemovesConnection(Scenario scenario) throws Exception
    {
        start(scenario, new EmptyServerHandler());

        String host = "localhost";
        int port = connector.getLocalPort();
        CountDownLatch beginLatch = new CountDownLatch(1);
        CountDownLatch failureLatch = new CountDownLatch(2);
        Request request = client.newRequest(host, port).scheme(scenario.getScheme());
        HttpDestination destination = (HttpDestination)client.resolveDestination(request);
        DuplexConnectionPool connectionPool = (DuplexConnectionPool)destination.getConnectionPool();

        assertEquals(0, connectionPool.getIdleConnections().size());
        assertEquals(0, connectionPool.getActiveConnections().size());

        request.listener(new Request.Listener.Adapter()
        {
            @Override
            public void onBegin(Request request)
            {
                connectionPool.getActiveConnections().iterator().next().close();
                beginLatch.countDown();
            }

            @Override
            public void onFailure(Request request, Throwable failure)
            {
                failureLatch.countDown();
            }
        }).send(new Response.Listener.Adapter()
        {
            @Override
            public void onComplete(Result result)
            {
                assertTrue(result.isFailed());
                assertEquals(0, connectionPool.getIdleConnections().size());
                assertEquals(0, connectionPool.getActiveConnections().size());
                failureLatch.countDown();
            }
        });

        assertTrue(beginLatch.await(30, TimeUnit.SECONDS));
        assertTrue(failureLatch.await(30, TimeUnit.SECONDS));

        assertEquals(0, connectionPool.getIdleConnections().size());
        assertEquals(0, connectionPool.getActiveConnections().size());
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testBadRequestRemovesConnection(Scenario scenario) throws Exception
    {
        start(scenario, new EmptyServerHandler());

        String host = "localhost";
        int port = connector.getLocalPort();
        CountDownLatch successLatch = new CountDownLatch(3);
        Request request = client.newRequest(host, port).scheme(scenario.getScheme());
        HttpDestination destination = (HttpDestination)client.resolveDestination(request);
        DuplexConnectionPool connectionPool = (DuplexConnectionPool)destination.getConnectionPool();

        Queue<Connection> idleConnections = connectionPool.getIdleConnections();
        assertEquals(0, idleConnections.size());

        Collection<Connection> activeConnections = connectionPool.getActiveConnections();
        assertEquals(0, activeConnections.size());

        request.listener(new Request.Listener.Adapter()
        {
            @Override
            public void onBegin(Request request)
            {
                // Remove the host header, this will make the request invalid
                request.headers(headers -> headers.remove(HttpHeader.HOST));
            }

            @Override
            public void onSuccess(Request request)
            {
                successLatch.countDown();
            }
        })
            .send(new Response.Listener.Adapter()
            {
                @Override
                public void onSuccess(Response response)
                {
                    assertEquals(400, response.getStatus());
                    // 400 response also come with a Connection: close,
                    // so the connection is closed and removed
                    successLatch.countDown();
                }

                @Override
                public void onComplete(Result result)
                {
                    assertFalse(result.isFailed());
                    successLatch.countDown();
                }
            });

        assertTrue(successLatch.await(30, TimeUnit.SECONDS));

        assertEquals(0, idleConnections.size());
        assertEquals(0, activeConnections.size());
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testBadRequestWithSlowRequestRemovesConnection(Scenario scenario) throws Exception
    {
        start(scenario, new EmptyServerHandler());

        String host = "localhost";
        int port = connector.getLocalPort();
        CountDownLatch successLatch = new CountDownLatch(3);
        Request request = client.newRequest(host, port).scheme(scenario.getScheme());
        HttpDestination destination = (HttpDestination)client.resolveDestination(request);
        DuplexConnectionPool connectionPool = (DuplexConnectionPool)destination.getConnectionPool();

        Collection<Connection> idleConnections = connectionPool.getIdleConnections();
        assertEquals(0, idleConnections.size());

        Collection<Connection> activeConnections = connectionPool.getActiveConnections();
        assertEquals(0, activeConnections.size());

        long delay = 1000;
        request.listener(new Request.Listener.Adapter()
        {
            @Override
            public void onBegin(Request request)
            {
                // Remove the host header, this will make the request invalid
                request.headers(headers -> headers.remove(HttpHeader.HOST));
            }

            @Override
            public void onHeaders(Request request)
            {
                try
                {
                    TimeUnit.MILLISECONDS.sleep(delay);
                }
                catch (InterruptedException e)
                {
                    e.printStackTrace();
                }
            }

            @Override
            public void onSuccess(Request request)
            {
                successLatch.countDown();
            }
        })
            .send(new Response.Listener.Adapter()
            {
                @Override
                public void onSuccess(Response response)
                {
                    assertEquals(400, response.getStatus());
                    // 400 response also come with a Connection: close,
                    // so the connection is closed and removed
                    successLatch.countDown();
                }

                @Override
                public void onComplete(Result result)
                {
                    assertFalse(result.isFailed());
                    successLatch.countDown();
                }
            });

        assertTrue(successLatch.await(delay * 30, TimeUnit.MILLISECONDS));

        assertEquals(0, idleConnections.size());
        assertEquals(0, activeConnections.size());
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testConnectionFailureRemovesConnection(Scenario scenario) throws Exception
    {
        start(scenario, new EmptyServerHandler());

        String host = "localhost";
        int port = connector.getLocalPort();
        Request request = client.newRequest(host, port).scheme(scenario.getScheme());
        HttpDestination destination = (HttpDestination)client.resolveDestination(request);
        DuplexConnectionPool connectionPool = (DuplexConnectionPool)destination.getConnectionPool();

        Collection<Connection> idleConnections = connectionPool.getIdleConnections();
        assertEquals(0, idleConnections.size());

        Collection<Connection> activeConnections = connectionPool.getActiveConnections();
        assertEquals(0, activeConnections.size());

        server.stop();

        CountDownLatch failureLatch = new CountDownLatch(2);
        request.onRequestFailure((r, x) -> failureLatch.countDown())
            .send(result ->
            {
                assertTrue(result.isFailed());
                failureLatch.countDown();
            });

        assertTrue(failureLatch.await(30, TimeUnit.SECONDS));

        assertEquals(0, idleConnections.size());
        assertEquals(0, activeConnections.size());
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testResponseWithConnectionCloseHeaderRemovesConnection(Scenario scenario) throws Exception
    {
        start(scenario, new AbstractHandler()
        {
            @Override
            public void handle(String target, org.eclipse.jetty.server.Request baseRequest, HttpServletRequest request, HttpServletResponse response)
            {
                response.setHeader("Connection", "close");
                baseRequest.setHandled(true);
            }
        });

        String host = "localhost";
        int port = connector.getLocalPort();
        Request request = client.newRequest(host, port).scheme(scenario.getScheme());
        HttpDestination destination = (HttpDestination)client.resolveDestination(request);
        DuplexConnectionPool connectionPool = (DuplexConnectionPool)destination.getConnectionPool();

        Collection<Connection> idleConnections = connectionPool.getIdleConnections();
        assertEquals(0, idleConnections.size());

        Collection<Connection> activeConnections = connectionPool.getActiveConnections();
        assertEquals(0, activeConnections.size());

        CountDownLatch latch = new CountDownLatch(1);
        request.send(new Response.Listener.Adapter()
        {
            @Override
            public void onComplete(Result result)
            {
                assertFalse(result.isFailed());
                assertEquals(0, idleConnections.size());
                assertEquals(0, activeConnections.size());
                latch.countDown();
            }
        });

        assertTrue(latch.await(30, TimeUnit.SECONDS));

        assertEquals(0, idleConnections.size());
        assertEquals(0, activeConnections.size());
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testBigRequestContentResponseWithConnectionCloseHeaderRemovesConnection(Scenario scenario) throws Exception
    {
        try (StacklessLogging ignore = new StacklessLogging(HttpConnection.class))
        {
            start(scenario, new AbstractHandler()
            {
                @Override
                public void handle(String target, org.eclipse.jetty.server.Request baseRequest, HttpServletRequest request, HttpServletResponse response)
                {
                    response.setHeader("Connection", "close");
                    baseRequest.setHandled(true);
                    // Don't read request content; this causes the server parser to be closed
                }
            });

            String host = "localhost";
            int port = connector.getLocalPort();
            Request request = client.newRequest(host, port).scheme(scenario.getScheme());
            HttpDestination destination = (HttpDestination)client.resolveDestination(request);
            DuplexConnectionPool connectionPool = (DuplexConnectionPool)destination.getConnectionPool();

            Collection<Connection> idleConnections = connectionPool.getIdleConnections();
            assertEquals(0, idleConnections.size());

            Collection<Connection> activeConnections = connectionPool.getActiveConnections();
            assertEquals(0, activeConnections.size());

            LOG.info("Expecting java.lang.IllegalStateException: HttpParser{s=CLOSED,...");

            CountDownLatch latch = new CountDownLatch(1);
            ByteBuffer buffer = ByteBuffer.allocate(16 * 1024 * 1024);
            Arrays.fill(buffer.array(), (byte)'x');
            request.body(new ByteBufferRequestContent(buffer))
                .send(new Response.Listener.Adapter()
                {
                    @Override
                    public void onComplete(Result result)
                    {
                        assertEquals(1, latch.getCount());
                        assertEquals(0, idleConnections.size());
                        assertEquals(0, activeConnections.size());
                        latch.countDown();
                    }
                });

            assertTrue(latch.await(30, TimeUnit.SECONDS));

            assertEquals(0, idleConnections.size());
            assertEquals(0, activeConnections.size());

            server.stop();
        }
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testIdleConnectionIsClosedOnRemoteClose(Scenario scenario) throws Exception
    {
        start(scenario, new EmptyServerHandler());

        String host = "localhost";
        int port = connector.getLocalPort();
        Request request = client.newRequest(host, port).scheme(scenario.getScheme());
        HttpDestination destination = (HttpDestination)client.resolveDestination(request);
        DuplexConnectionPool connectionPool = (DuplexConnectionPool)destination.getConnectionPool();

        Collection<Connection> idleConnections = connectionPool.getIdleConnections();
        assertEquals(0, idleConnections.size());

        Collection<Connection> activeConnections = connectionPool.getActiveConnections();
        assertEquals(0, activeConnections.size());

        ContentResponse response = request.timeout(30, TimeUnit.SECONDS).send();

        assertEquals(200, response.getStatus());

        connector.stop();

        // Give the connection some time to process the remote close
        await().atMost(5, TimeUnit.SECONDS).until(() -> idleConnections.size() == 0 && activeConnections.size() == 0);
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testConnectionForHTTP10ResponseIsRemoved(Scenario scenario) throws Exception
    {
        start(scenario, new EmptyServerHandler());

        String host = "localhost";
        int port = connector.getLocalPort();
        Request request = client.newRequest(host, port).scheme(scenario.getScheme());
        HttpDestination destination = (HttpDestination)client.resolveDestination(request);
        DuplexConnectionPool connectionPool = (DuplexConnectionPool)destination.getConnectionPool();

        Collection<Connection> idleConnections = connectionPool.getIdleConnections();
        assertEquals(0, idleConnections.size());

        Collection<Connection> activeConnections = connectionPool.getActiveConnections();
        assertEquals(0, activeConnections.size());

        client.setStrictEventOrdering(false);
        ContentResponse response = request
            .onResponseBegin(response1 ->
            {
                // Simulate an HTTP 1.0 response has been received.
                ((HttpResponse)response1).version(HttpVersion.HTTP_1_0);
            })
            .send();

        assertEquals(200, response.getStatus());

        assertEquals(0, idleConnections.size());
        assertEquals(0, activeConnections.size());
    }
}
