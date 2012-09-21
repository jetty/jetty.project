//
//  ========================================================================
//  Copyright (c) 1995-2012 Mort Bay Consulting Pty. Ltd.
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
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.client.api.Connection;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.client.api.Result;
import org.eclipse.jetty.client.util.ByteBufferContentProvider;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.util.log.StdErrLog;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.junit.Assert;
import org.junit.Test;

public class HttpConnectionLifecycleTest extends AbstractHttpClientServerTest
{
    public HttpConnectionLifecycleTest(SslContextFactory sslContextFactory)
    {
        super(sslContextFactory);
    }

    @Test
    public void test_SuccessfulRequest_ReturnsConnection() throws Exception
    {
        start(new EmptyServerHandler());

        String host = "localhost";
        int port = connector.getLocalPort();
        HttpDestination destination = (HttpDestination)client.getDestination(scheme, host, port);

        final BlockingQueue<Connection> idleConnections = destination.getIdleConnections();
        Assert.assertEquals(0, idleConnections.size());

        final BlockingQueue<Connection> activeConnections = destination.getActiveConnections();
        Assert.assertEquals(0, activeConnections.size());

        final CountDownLatch headersLatch = new CountDownLatch(1);
        final CountDownLatch successLatch = new CountDownLatch(3);
        client.newRequest(host, port)
                .scheme(scheme)
                .listener(new Request.Listener.Empty()
                {
                    @Override
                    public void onSuccess(Request request)
                    {
                        successLatch.countDown();
                    }
                })
                .send(new Response.Listener.Empty()
                {
                    @Override
                    public void onHeaders(Response response)
                    {
                        Assert.assertEquals(0, idleConnections.size());
                        Assert.assertEquals(1, activeConnections.size());
                        headersLatch.countDown();
                    }

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

        Assert.assertTrue(headersLatch.await(5, TimeUnit.SECONDS));
        Assert.assertTrue(successLatch.await(5, TimeUnit.SECONDS));

        Assert.assertEquals(1, idleConnections.size());
        Assert.assertEquals(0, activeConnections.size());
    }

    @Test
    public void test_FailedRequest_RemovesConnection() throws Exception
    {
        start(new EmptyServerHandler());

        String host = "localhost";
        int port = connector.getLocalPort();
        HttpDestination destination = (HttpDestination)client.getDestination(scheme, host, port);

        final BlockingQueue<Connection> idleConnections = destination.getIdleConnections();
        Assert.assertEquals(0, idleConnections.size());

        final BlockingQueue<Connection> activeConnections = destination.getActiveConnections();
        Assert.assertEquals(0, activeConnections.size());

        final CountDownLatch beginLatch = new CountDownLatch(1);
        final CountDownLatch failureLatch = new CountDownLatch(2);
        client.newRequest(host, port).scheme(scheme).listener(new Request.Listener.Empty()
        {
            @Override
            public void onBegin(Request request)
            {
                activeConnections.peek().close();
                beginLatch.countDown();
            }

            @Override
            public void onFailure(Request request, Throwable failure)
            {
                failureLatch.countDown();
            }
        }).send(new Response.Listener.Empty()
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

        Assert.assertTrue(beginLatch.await(5, TimeUnit.SECONDS));
        Assert.assertTrue(failureLatch.await(5, TimeUnit.SECONDS));

        Assert.assertEquals(0, idleConnections.size());
        Assert.assertEquals(0, activeConnections.size());
    }

    @Test
    public void test_BadRequest_RemovesConnection() throws Exception
    {
        start(new EmptyServerHandler());

        String host = "localhost";
        int port = connector.getLocalPort();
        HttpDestination destination = (HttpDestination)client.getDestination(scheme, host, port);

        final BlockingQueue<Connection> idleConnections = destination.getIdleConnections();
        Assert.assertEquals(0, idleConnections.size());

        final BlockingQueue<Connection> activeConnections = destination.getActiveConnections();
        Assert.assertEquals(0, activeConnections.size());

        final CountDownLatch successLatch = new CountDownLatch(3);
        client.newRequest(host, port)
                .scheme(scheme)
                .listener(new Request.Listener.Empty()
                {
                    @Override
                    public void onBegin(Request request)
                    {
                        // Remove the host header, this will make the request invalid
                        request.header(HttpHeader.HOST.asString(), null);
                    }

                    @Override
                    public void onSuccess(Request request)
                    {
                        successLatch.countDown();
                    }
                })
                .send(new Response.Listener.Empty()
                {
                    @Override
                    public void onSuccess(Response response)
                    {
                        Assert.assertEquals(400, response.status());
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

        Assert.assertTrue(successLatch.await(5, TimeUnit.SECONDS));

        Assert.assertEquals(0, idleConnections.size());
        Assert.assertEquals(0, activeConnections.size());
    }

    @Test
    public void test_ConnectionFailure_RemovesConnection() throws Exception
    {
        start(new EmptyServerHandler());

        String host = "localhost";
        int port = connector.getLocalPort();
        HttpDestination destination = (HttpDestination)client.getDestination(scheme, host, port);

        final BlockingQueue<Connection> idleConnections = destination.getIdleConnections();
        Assert.assertEquals(0, idleConnections.size());

        final BlockingQueue<Connection> activeConnections = destination.getActiveConnections();
        Assert.assertEquals(0, activeConnections.size());

        server.stop();

        final CountDownLatch failureLatch = new CountDownLatch(2);
        client.newRequest(host, port)
                .scheme(scheme)
                .listener(new Request.Listener.Empty()
                {
                    @Override
                    public void onFailure(Request request, Throwable failure)
                    {
                        failureLatch.countDown();
                    }
                })
                .send(new Response.Listener.Empty()
                {
                    @Override
                    public void onComplete(Result result)
                    {
                        Assert.assertTrue(result.isFailed());
                        failureLatch.countDown();
                    }
                });

        Assert.assertTrue(failureLatch.await(5, TimeUnit.SECONDS));

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
        HttpDestination destination = (HttpDestination)client.getDestination(scheme, host, port);

        final BlockingQueue<Connection> idleConnections = destination.getIdleConnections();
        Assert.assertEquals(0, idleConnections.size());

        final BlockingQueue<Connection> activeConnections = destination.getActiveConnections();
        Assert.assertEquals(0, activeConnections.size());

        final CountDownLatch latch = new CountDownLatch(1);
        client.newRequest(host, port)
                .scheme(scheme)
                .send(new Response.Listener.Empty()
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

        Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));

        Assert.assertEquals(0, idleConnections.size());
        Assert.assertEquals(0, activeConnections.size());
    }

    @Test
    public void test_BigRequestContent_ResponseWithConnectionCloseHeader_RemovesConnection() throws Exception
    {
        StdErrLog logger = StdErrLog.getLogger(org.eclipse.jetty.server.HttpConnection.class);
        logger.setHideStacks(true);
        try
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
            HttpDestination destination = (HttpDestination)client.getDestination(scheme, host, port);

            final BlockingQueue<Connection> idleConnections = destination.getIdleConnections();
            Assert.assertEquals(0, idleConnections.size());

            final BlockingQueue<Connection> activeConnections = destination.getActiveConnections();
            Assert.assertEquals(0, activeConnections.size());

            final CountDownLatch latch = new CountDownLatch(1);
            client.newRequest(host, port)
                    .scheme(scheme)
                    .content(new ByteBufferContentProvider(ByteBuffer.allocate(16 * 1024 * 1024)))
                    .send(new Response.Listener.Empty()
                    {
                        @Override
                        public void onComplete(Result result)
                        {
                            Assert.assertEquals(0, idleConnections.size());
                            Assert.assertEquals(0, activeConnections.size());
                            latch.countDown();
                        }
                    });

            Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));

            Assert.assertEquals(0, idleConnections.size());
            Assert.assertEquals(0, activeConnections.size());
        }
        finally
        {
            logger.setHideStacks(false);
        }
    }
}
