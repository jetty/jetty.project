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

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Collection;
import java.util.Queue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.client.api.Connection;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.client.api.Result;
import org.eclipse.jetty.client.http.HttpDestinationOverHTTP;
import org.eclipse.jetty.client.util.ByteBufferContentProvider;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.StacklessLogging;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.condition.DisabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class HttpConnectionLifecycleTest extends AbstractHttpClientServerTest
{
    @Override
    public HttpClient newHttpClient(Scenario scenario, HttpClientTransport transport)
    {
        HttpClient client = super.newHttpClient(scenario, transport);
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
        HttpDestinationOverHTTP destination = (HttpDestinationOverHTTP)client.getDestination(scenario.getScheme(), host, port);

        assertEquals(0, ((DuplexConnectionPool)destination.getConnectionPool()).getIdleConnections().size());
        assertEquals(0, ((DuplexConnectionPool)destination.getConnectionPool()).getActiveConnections().size());

        final CountDownLatch headersLatch = new CountDownLatch(1);
        final CountDownLatch successLatch = new CountDownLatch(3);
        client.newRequest(host, port)
            .scheme(scenario.getScheme())
            .onRequestSuccess(request -> successLatch.countDown())
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
        HttpDestinationOverHTTP destination = (HttpDestinationOverHTTP)client.getDestination(scenario.getScheme(), host, port);
        DuplexConnectionPool connectionPool = (DuplexConnectionPool)destination.getConnectionPool();

        assertEquals(0, connectionPool.getIdleConnections().size());
        assertEquals(0, connectionPool.getActiveConnections().size());

        final CountDownLatch beginLatch = new CountDownLatch(1);
        final CountDownLatch failureLatch = new CountDownLatch(2);
        client.newRequest(host, port).scheme(scenario.getScheme()).listener(new Request.Listener.Adapter()
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
        HttpDestinationOverHTTP destination = (HttpDestinationOverHTTP)client.getDestination(scenario.getScheme(), host, port);
        DuplexConnectionPool connectionPool = (DuplexConnectionPool)destination.getConnectionPool();

        final Queue<Connection> idleConnections = connectionPool.getIdleConnections();
        assertEquals(0, idleConnections.size());

        final Collection<Connection> activeConnections = connectionPool.getActiveConnections();
        assertEquals(0, activeConnections.size());

        final CountDownLatch successLatch = new CountDownLatch(3);
        client.newRequest(host, port)
            .scheme(scenario.getScheme())
            .listener(new Request.Listener.Adapter()
            {
                @Override
                public void onBegin(Request request)
                {
                    // Remove the host header, this will make the request invalid
                    request.header(HttpHeader.HOST, null);
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
    @Tag("Slow")
    @DisabledIfSystemProperty(named = "env", matches = "ci") // TODO: SLOW, needs review
    public void testBadRequestWithSlowRequestRemovesConnection(Scenario scenario) throws Exception
    {
        start(scenario, new EmptyServerHandler());

        String host = "localhost";
        int port = connector.getLocalPort();
        HttpDestinationOverHTTP destination = (HttpDestinationOverHTTP)client.getDestination(scenario.getScheme(), host, port);
        DuplexConnectionPool connectionPool = (DuplexConnectionPool)destination.getConnectionPool();

        final Collection<Connection> idleConnections = connectionPool.getIdleConnections();
        assertEquals(0, idleConnections.size());

        final Collection<Connection> activeConnections = connectionPool.getActiveConnections();
        assertEquals(0, activeConnections.size());

        final long delay = 1000;
        final CountDownLatch successLatch = new CountDownLatch(3);
        client.newRequest(host, port)
            .scheme(scenario.getScheme())
            .listener(new Request.Listener.Adapter()
            {
                @Override
                public void onBegin(Request request)
                {
                    // Remove the host header, this will make the request invalid
                    request.header(HttpHeader.HOST, null);
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
        HttpDestinationOverHTTP destination = (HttpDestinationOverHTTP)client.getDestination(scenario.getScheme(), host, port);
        DuplexConnectionPool connectionPool = (DuplexConnectionPool)destination.getConnectionPool();

        final Collection<Connection> idleConnections = connectionPool.getIdleConnections();
        assertEquals(0, idleConnections.size());

        final Collection<Connection> activeConnections = connectionPool.getActiveConnections();
        assertEquals(0, activeConnections.size());

        server.stop();

        final CountDownLatch failureLatch = new CountDownLatch(2);
        client.newRequest(host, port)
            .scheme(scenario.getScheme())
            .onRequestFailure((request, failure) -> failureLatch.countDown())
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
        HttpDestinationOverHTTP destination = (HttpDestinationOverHTTP)client.getDestination(scenario.getScheme(), host, port);
        DuplexConnectionPool connectionPool = (DuplexConnectionPool)destination.getConnectionPool();

        final Collection<Connection> idleConnections = connectionPool.getIdleConnections();
        assertEquals(0, idleConnections.size());

        final Collection<Connection> activeConnections = connectionPool.getActiveConnections();
        assertEquals(0, activeConnections.size());

        final CountDownLatch latch = new CountDownLatch(1);
        client.newRequest(host, port)
            .scheme(scenario.getScheme())
            .send(new Response.Listener.Adapter()
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
            HttpDestinationOverHTTP destination = (HttpDestinationOverHTTP)client.getDestination(scenario.getScheme(), host, port);
            DuplexConnectionPool connectionPool = (DuplexConnectionPool)destination.getConnectionPool();

            final Collection<Connection> idleConnections = connectionPool.getIdleConnections();
            assertEquals(0, idleConnections.size());

            final Collection<Connection> activeConnections = connectionPool.getActiveConnections();
            assertEquals(0, activeConnections.size());

            Log.getLogger(HttpConnection.class).info("Expecting java.lang.IllegalStateException: HttpParser{s=CLOSED,...");

            final CountDownLatch latch = new CountDownLatch(1);
            ByteBuffer buffer = ByteBuffer.allocate(16 * 1024 * 1024);
            Arrays.fill(buffer.array(), (byte)'x');
            client.newRequest(host, port)
                .scheme(scenario.getScheme())
                .content(new ByteBufferContentProvider(buffer))
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
    @Tag("Slow")
    @DisabledIfSystemProperty(named = "env", matches = "ci") // TODO: SLOW, needs review
    public void testIdleConnectionIsClosedOnRemoteClose(Scenario scenario) throws Exception
    {
        start(scenario, new EmptyServerHandler());

        String host = "localhost";
        int port = connector.getLocalPort();
        HttpDestinationOverHTTP destination = (HttpDestinationOverHTTP)client.getDestination(scenario.getScheme(), host, port);
        DuplexConnectionPool connectionPool = (DuplexConnectionPool)destination.getConnectionPool();

        final Collection<Connection> idleConnections = connectionPool.getIdleConnections();
        assertEquals(0, idleConnections.size());

        final Collection<Connection> activeConnections = connectionPool.getActiveConnections();
        assertEquals(0, activeConnections.size());

        ContentResponse response = client.newRequest(host, port)
            .scheme(scenario.getScheme())
            .timeout(30, TimeUnit.SECONDS)
            .send();

        assertEquals(200, response.getStatus());

        connector.stop();

        // Give the connection some time to process the remote close
        TimeUnit.SECONDS.sleep(1);

        assertEquals(0, idleConnections.size());
        assertEquals(0, activeConnections.size());
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testConnectionForHTTP10ResponseIsRemoved(Scenario scenario) throws Exception
    {
        start(scenario, new EmptyServerHandler());

        String host = "localhost";
        int port = connector.getLocalPort();
        HttpDestinationOverHTTP destination = (HttpDestinationOverHTTP)client.getDestination(scenario.getScheme(), host, port);
        DuplexConnectionPool connectionPool = (DuplexConnectionPool)destination.getConnectionPool();

        final Collection<Connection> idleConnections = connectionPool.getIdleConnections();
        assertEquals(0, idleConnections.size());

        final Collection<Connection> activeConnections = connectionPool.getActiveConnections();
        assertEquals(0, activeConnections.size());

        client.setStrictEventOrdering(false);
        ContentResponse response = client.newRequest(host, port)
            .scheme(scenario.getScheme())
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
