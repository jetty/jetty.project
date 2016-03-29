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

import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.client.api.Connection;
import org.eclipse.jetty.client.http.HttpClientTransportOverHTTP;
import org.eclipse.jetty.client.http.HttpConnectionOverHTTP;
import org.eclipse.jetty.client.util.DeferredContentProvider;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;

public class HttpClientFailureTest
{
    private Server server;
    private ServerConnector connector;
    private HttpClient client;

    private void startServer(Handler handler) throws Exception
    {
        QueuedThreadPool serverThreads = new QueuedThreadPool();
        serverThreads.setName("server");
        server = new Server(serverThreads);
        connector = new ServerConnector(server);
        server.addConnector(connector);
        server.setHandler(handler);
        server.start();
    }

    @After
    public void dispose() throws Exception
    {
        if (server != null)
            server.stop();
        if (client != null)
            client.stop();
    }

    @Test
    public void testFailureBeforeRequestCommit() throws Exception
    {
        startServer(new EmptyServerHandler());

        final AtomicReference<HttpConnectionOverHTTP> connectionRef = new AtomicReference<>();
        client = new HttpClient(new HttpClientTransportOverHTTP()
        {
            @Override
            protected HttpConnectionOverHTTP newHttpConnection(EndPoint endPoint, HttpDestination destination, Promise<Connection> promise)
            {
                HttpConnectionOverHTTP connection = super.newHttpConnection(endPoint, destination, promise);
                connectionRef.set(connection);
                return connection;
            }
        }, null);
        client.start();

        try
        {
            client.newRequest("localhost", connector.getLocalPort())
                    .onRequestHeaders(request -> connectionRef.get().getEndPoint().close())
                    .timeout(5, TimeUnit.SECONDS)
                    .send();
            Assert.fail();
        }
        catch (ExecutionException x)
        {
            // Expected.
        }

        DuplexConnectionPool connectionPool = (DuplexConnectionPool)connectionRef.get().getHttpDestination().getConnectionPool();
        Assert.assertEquals(0, connectionPool.getConnectionCount());
        Assert.assertEquals(0, connectionPool.getActiveConnections().size());
        Assert.assertEquals(0, connectionPool.getIdleConnections().size());
    }

    @Test
    public void testFailureAfterRequestCommit() throws Exception
    {
        startServer(new EmptyServerHandler());

        final AtomicReference<HttpConnectionOverHTTP> connectionRef = new AtomicReference<>();
        client = new HttpClient(new HttpClientTransportOverHTTP()
        {
            @Override
            protected HttpConnectionOverHTTP newHttpConnection(EndPoint endPoint, HttpDestination destination, Promise<Connection> promise)
            {
                HttpConnectionOverHTTP connection = super.newHttpConnection(endPoint, destination, promise);
                connectionRef.set(connection);
                return connection;
            }
        }, null);
        client.start();

        final CountDownLatch commitLatch = new CountDownLatch(1);
        final CountDownLatch completeLatch = new CountDownLatch(1);
        DeferredContentProvider content = new DeferredContentProvider();
        client.newRequest("localhost", connector.getLocalPort())
                .onRequestCommit(request ->
                {
                    connectionRef.get().getEndPoint().close();
                    commitLatch.countDown();
                })
                .content(content)
                .idleTimeout(2, TimeUnit.SECONDS)
                .send(result ->
                {
                    if (result.isFailed())
                        completeLatch.countDown();
                });

        Assert.assertTrue(commitLatch.await(5, TimeUnit.SECONDS));
        final CountDownLatch contentLatch = new CountDownLatch(1);
        content.offer(ByteBuffer.allocate(1024), new Callback()
        {
            @Override
            public void failed(Throwable x)
            {
                contentLatch.countDown();
            }
        });

        Assert.assertTrue(commitLatch.await(5, TimeUnit.SECONDS));
        Assert.assertTrue(contentLatch.await(5, TimeUnit.SECONDS));
        Assert.assertTrue(completeLatch.await(5, TimeUnit.SECONDS));

        DuplexConnectionPool connectionPool = (DuplexConnectionPool)connectionRef.get().getHttpDestination().getConnectionPool();
        Assert.assertEquals(0, connectionPool.getConnectionCount());
        Assert.assertEquals(0, connectionPool.getActiveConnections().size());
        Assert.assertEquals(0, connectionPool.getIdleConnections().size());
    }
/*
    @Test
    public void test_ExchangeIsComplete_WhenRequestFailsMidway_WithResponse() throws Exception
    {
        start(new AbstractHandler()
        {
            @Override
            public void handle(String target, org.eclipse.jetty.server.Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                // Echo back
                IO.copy(request.getInputStream(), response.getOutputStream());
            }
        });

        final CountDownLatch latch = new CountDownLatch(1);
        client.newRequest("localhost", connector.getLocalPort())
                .scheme(scheme)
                        // The second ByteBuffer set to null will throw an exception
                .content(new ContentProvider()
                {
                    @Override
                    public long getLength()
                    {
                        return -1;
                    }

                    @Override
                    public Iterator<ByteBuffer> iterator()
                    {
                        return new Iterator<ByteBuffer>()
                        {
                            @Override
                            public boolean hasNext()
                            {
                                return true;
                            }

                            @Override
                            public ByteBuffer next()
                            {
                                throw new NoSuchElementException("explicitly_thrown_by_test");
                            }

                            @Override
                            public void remove()
                            {
                                throw new UnsupportedOperationException();
                            }
                        };
                    }
                })
                .send(new Response.Listener.Adapter()
                {
                    @Override
                    public void onComplete(Result result)
                    {
                        latch.countDown();
                    }
                });

        Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void test_ExchangeIsComplete_WhenRequestFails_WithNoResponse() throws Exception
    {
        start(new EmptyServerHandler());

        final CountDownLatch latch = new CountDownLatch(1);
        final String host = "localhost";
        final int port = connector.getLocalPort();
        client.newRequest(host, port)
                .scheme(scheme)
                .onRequestBegin(new Request.BeginListener()
                {
                    @Override
                    public void onBegin(Request request)
                    {
                        HttpDestinationOverHTTP destination = (HttpDestinationOverHTTP)client.getDestination(scheme, host, port);
                        destination.getConnectionPool().getActiveConnections().peek().close();
                    }
                })
                .send(new Response.Listener.Adapter()
                {
                    @Override
                    public void onComplete(Result result)
                    {
                        latch.countDown();
                    }
                });

        Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));
    }
*/
}
