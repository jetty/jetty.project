//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Collection;
import java.util.Queue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.servlet.ServletException;
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
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.toolchain.test.annotation.Slow;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.StacklessLogging;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.junit.Assert;
import org.junit.Test;

public class HttpConnectionLifecycleTest extends AbstractHttpClientServerTest
{
    public HttpConnectionLifecycleTest(SslContextFactory sslContextFactory)
    {
        super(sslContextFactory);
    }

    @Override
    public void start(Handler handler) throws Exception
    {
        super.start(handler);
        client.setStrictEventOrdering(false);
    }

    @Test
    public void test_SuccessfulRequest_ReturnsConnection() throws Exception
    {
        start(new EmptyServerHandler());

        String host = "localhost";
        int port = connector.getLocalPort();
        HttpDestinationOverHTTP destination = (HttpDestinationOverHTTP)client.getDestination(scheme, host, port);
        DuplexConnectionPool connectionPool = (DuplexConnectionPool)destination.getConnectionPool();

        final Collection<Connection> idleConnections = connectionPool.getIdleConnections();
        Assert.assertEquals(0, idleConnections.size());

        final Collection<Connection> activeConnections = connectionPool.getActiveConnections();
        Assert.assertEquals(0, activeConnections.size());

        final CountDownLatch headersLatch = new CountDownLatch(1);
        final CountDownLatch successLatch = new CountDownLatch(3);
        client.newRequest(host, port)
                .scheme(scheme)
                .onRequestSuccess(request -> successLatch.countDown())
                .onResponseHeaders(response ->
                {
                    Assert.assertEquals(0, idleConnections.size());
                    Assert.assertEquals(1, activeConnections.size());
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
                        Assert.assertFalse(result.isFailed());
                        successLatch.countDown();
                    }
                });

        Assert.assertTrue(headersLatch.await(30, TimeUnit.SECONDS));
        Assert.assertTrue(successLatch.await(30, TimeUnit.SECONDS));

        Assert.assertEquals(1, idleConnections.size());
        Assert.assertEquals(0, activeConnections.size());
    }

    @Test
    public void test_FailedRequest_RemovesConnection() throws Exception
    {
        start(new EmptyServerHandler());

        String host = "localhost";
        int port = connector.getLocalPort();
        HttpDestinationOverHTTP destination = (HttpDestinationOverHTTP)client.getDestination(scheme, host, port);
        DuplexConnectionPool connectionPool = (DuplexConnectionPool)destination.getConnectionPool();

        final Collection<Connection> idleConnections = connectionPool.getIdleConnections();
        Assert.assertEquals(0, idleConnections.size());

        final Collection<Connection> activeConnections = connectionPool.getActiveConnections();
        Assert.assertEquals(0, activeConnections.size());

        final CountDownLatch beginLatch = new CountDownLatch(1);
        final CountDownLatch failureLatch = new CountDownLatch(2);
        client.newRequest(host, port).scheme(scheme).listener(new Request.Listener.Adapter()
        {
            @Override
            public void onBegin(Request request)
            {
                activeConnections.iterator().next().close();
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
                Assert.assertTrue(result.isFailed());
                Assert.assertEquals(0, idleConnections.size());
                Assert.assertEquals(0, activeConnections.size());
                failureLatch.countDown();
            }
        });

        Assert.assertTrue(beginLatch.await(30, TimeUnit.SECONDS));
        Assert.assertTrue(failureLatch.await(30, TimeUnit.SECONDS));

        Assert.assertEquals(0, idleConnections.size());
        Assert.assertEquals(0, activeConnections.size());
    }

    @Test
    public void test_BadRequest_RemovesConnection() throws Exception
    {
        start(new EmptyServerHandler());

        String host = "localhost";
        int port = connector.getLocalPort();
        HttpDestinationOverHTTP destination = (HttpDestinationOverHTTP)client.getDestination(scheme, host, port);
        DuplexConnectionPool connectionPool = (DuplexConnectionPool)destination.getConnectionPool();

        final Queue<Connection> idleConnections = connectionPool.getIdleConnections();
        Assert.assertEquals(0, idleConnections.size());

        final Collection<Connection> activeConnections = connectionPool.getActiveConnections();
        Assert.assertEquals(0, activeConnections.size());

        final CountDownLatch successLatch = new CountDownLatch(3);
        client.newRequest(host, port)
                .scheme(scheme)
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
                        Assert.assertEquals(400, response.getStatus());
                        // 400 response also come with a Connection: close,
                        // so the connection is closed and removed
                        successLatch.countDown();
                    }

                    @Override
                    public void onComplete(Result result)
                    {
                        Assert.assertFalse(result.isFailed());
                        successLatch.countDown();
                    }
                });

        Assert.assertTrue(successLatch.await(30, TimeUnit.SECONDS));

        Assert.assertEquals(0, idleConnections.size());
        Assert.assertEquals(0, activeConnections.size());
    }

    @Slow
    @Test
    public void test_BadRequest_WithSlowRequest_RemovesConnection() throws Exception
    {
        start(new EmptyServerHandler());

        String host = "localhost";
        int port = connector.getLocalPort();
        HttpDestinationOverHTTP destination = (HttpDestinationOverHTTP)client.getDestination(scheme, host, port);
        DuplexConnectionPool connectionPool = (DuplexConnectionPool)destination.getConnectionPool();

        final Collection<Connection> idleConnections = connectionPool.getIdleConnections();
        Assert.assertEquals(0, idleConnections.size());

        final Collection<Connection> activeConnections = connectionPool.getActiveConnections();
        Assert.assertEquals(0, activeConnections.size());

        final long delay = 1000;
        final CountDownLatch successLatch = new CountDownLatch(3);
        client.newRequest(host, port)
                .scheme(scheme)
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
                        Assert.assertEquals(400, response.getStatus());
                        // 400 response also come with a Connection: close,
                        // so the connection is closed and removed
                        successLatch.countDown();
                    }

                    @Override
                    public void onComplete(Result result)
                    {
                        Assert.assertFalse(result.isFailed());
                        successLatch.countDown();
                    }
                });

        Assert.assertTrue(successLatch.await(delay * 30, TimeUnit.MILLISECONDS));

        Assert.assertEquals(0, idleConnections.size());
        Assert.assertEquals(0, activeConnections.size());
    }

    @Test
    public void test_ConnectionFailure_RemovesConnection() throws Exception
    {
        start(new EmptyServerHandler());

        String host = "localhost";
        int port = connector.getLocalPort();
        HttpDestinationOverHTTP destination = (HttpDestinationOverHTTP)client.getDestination(scheme, host, port);
        DuplexConnectionPool connectionPool = (DuplexConnectionPool)destination.getConnectionPool();

        final Collection<Connection> idleConnections = connectionPool.getIdleConnections();
        Assert.assertEquals(0, idleConnections.size());

        final Collection<Connection> activeConnections = connectionPool.getActiveConnections();
        Assert.assertEquals(0, activeConnections.size());

        server.stop();

        final CountDownLatch failureLatch = new CountDownLatch(2);
        client.newRequest(host, port)
                .scheme(scheme)
                .onRequestFailure((request, failure) -> failureLatch.countDown())
                .send(result ->
                {
                    Assert.assertTrue(result.isFailed());
                    failureLatch.countDown();
                });

        Assert.assertTrue(failureLatch.await(30, TimeUnit.SECONDS));

        Assert.assertEquals(0, idleConnections.size());
        Assert.assertEquals(0, activeConnections.size());
    }

    @Test
    public void test_ResponseWithConnectionCloseHeader_RemovesConnection() throws Exception
    {
        start(new AbstractHandler()
        {
            @Override
            public void handle(String target, org.eclipse.jetty.server.Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                response.setHeader("Connection", "close");
                baseRequest.setHandled(true);
            }
        });

        String host = "localhost";
        int port = connector.getLocalPort();
        HttpDestinationOverHTTP destination = (HttpDestinationOverHTTP)client.getDestination(scheme, host, port);
        DuplexConnectionPool connectionPool = (DuplexConnectionPool)destination.getConnectionPool();

        final Collection<Connection> idleConnections = connectionPool.getIdleConnections();
        Assert.assertEquals(0, idleConnections.size());

        final Collection<Connection> activeConnections = connectionPool.getActiveConnections();
        Assert.assertEquals(0, activeConnections.size());

        final CountDownLatch latch = new CountDownLatch(1);
        client.newRequest(host, port)
                .scheme(scheme)
                .send(new Response.Listener.Adapter()
                {
                    @Override
                    public void onComplete(Result result)
                    {
                        Assert.assertFalse(result.isFailed());
                        Assert.assertEquals(0, idleConnections.size());
                        Assert.assertEquals(0, activeConnections.size());
                        latch.countDown();
                    }
                });

        Assert.assertTrue(latch.await(30, TimeUnit.SECONDS));

        Assert.assertEquals(0, idleConnections.size());
        Assert.assertEquals(0, activeConnections.size());
    }

    @Test
    public void test_BigRequestContent_ResponseWithConnectionCloseHeader_RemovesConnection() throws Exception
    {
        try (StacklessLogging stackless = new StacklessLogging(HttpConnection.class))
        {
            start(new AbstractHandler()
            {
                @Override
                public void handle(String target, org.eclipse.jetty.server.Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
                {
                    response.setHeader("Connection", "close");
                    baseRequest.setHandled(true);
                    // Don't read request content; this causes the server parser to be closed
                }
            });

            String host = "localhost";
            int port = connector.getLocalPort();
            HttpDestinationOverHTTP destination = (HttpDestinationOverHTTP)client.getDestination(scheme, host, port);
            DuplexConnectionPool connectionPool = (DuplexConnectionPool)destination.getConnectionPool();

            final Collection<Connection> idleConnections = connectionPool.getIdleConnections();
            Assert.assertEquals(0, idleConnections.size());

            final Collection<Connection> activeConnections = connectionPool.getActiveConnections();
            Assert.assertEquals(0, activeConnections.size());

            Log.getLogger(HttpConnection.class).info("Expecting java.lang.IllegalStateException: HttpParser{s=CLOSED,...");

            final CountDownLatch latch = new CountDownLatch(1);
            ByteBuffer buffer = ByteBuffer.allocate(16 * 1024 * 1024);
            Arrays.fill(buffer.array(),(byte)'x');
            client.newRequest(host, port)
                    .scheme(scheme)
                    .content(new ByteBufferContentProvider(buffer))
                    .send(new Response.Listener.Adapter()
                    {
                        @Override
                        public void onComplete(Result result)
                        {
                            Assert.assertEquals(1, latch.getCount());
                            Assert.assertEquals(0, idleConnections.size());
                            Assert.assertEquals(0, activeConnections.size());
                            latch.countDown();
                        }
                    });

            Assert.assertTrue(latch.await(30, TimeUnit.SECONDS));

            Assert.assertEquals(0, idleConnections.size());
            Assert.assertEquals(0, activeConnections.size());

            server.stop();
        }
    }

    @Slow
    @Test
    public void test_IdleConnection_IsClosed_OnRemoteClose() throws Exception
    {
        start(new EmptyServerHandler());

        String host = "localhost";
        int port = connector.getLocalPort();
        HttpDestinationOverHTTP destination = (HttpDestinationOverHTTP)client.getDestination(scheme, host, port);
        DuplexConnectionPool connectionPool = (DuplexConnectionPool)destination.getConnectionPool();

        final Collection<Connection> idleConnections = connectionPool.getIdleConnections();
        Assert.assertEquals(0, idleConnections.size());

        final Collection<Connection> activeConnections = connectionPool.getActiveConnections();
        Assert.assertEquals(0, activeConnections.size());

        ContentResponse response = client.newRequest(host, port)
                .scheme(scheme)
                .timeout(30, TimeUnit.SECONDS)
                .send();

        Assert.assertEquals(200, response.getStatus());

        connector.stop();

        // Give the connection some time to process the remote close
        TimeUnit.SECONDS.sleep(1);

        Assert.assertEquals(0, idleConnections.size());
        Assert.assertEquals(0, activeConnections.size());
    }

    @Test
    public void testConnectionForHTTP10ResponseIsRemoved() throws Exception
    {
        start(new EmptyServerHandler());

        String host = "localhost";
        int port = connector.getLocalPort();
        HttpDestinationOverHTTP destination = (HttpDestinationOverHTTP)client.getDestination(scheme, host, port);
        DuplexConnectionPool connectionPool = (DuplexConnectionPool)destination.getConnectionPool();

        final Collection<Connection> idleConnections = connectionPool.getIdleConnections();
        Assert.assertEquals(0, idleConnections.size());

        final Collection<Connection> activeConnections = connectionPool.getActiveConnections();
        Assert.assertEquals(0, activeConnections.size());

        client.setStrictEventOrdering(false);
        ContentResponse response = client.newRequest(host, port)
                .scheme(scheme)
                .onResponseBegin(response1 ->
                {
                    // Simulate a HTTP 1.0 response has been received.
                    ((HttpResponse)response1).version(HttpVersion.HTTP_1_0);
                })
                .send();

        Assert.assertEquals(200, response.getStatus());

        Assert.assertEquals(0, idleConnections.size());
        Assert.assertEquals(0, activeConnections.size());
    }
}
