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
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.servlet.ServletException;
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
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.junit.Assert;
import org.junit.Test;

public class ClientConnectionCloseTest extends AbstractHttpClientServerTest
{
    public ClientConnectionCloseTest(SslContextFactory sslContextFactory)
    {
        super(sslContextFactory);
    }

    @Test
    public void test_ClientConnectionClose_ServerConnectionClose_ClientClosesAfterExchange() throws Exception
    {
        byte[] data = new byte[128 * 1024];
        start(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
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

        HttpDestinationOverHTTP destination = (HttpDestinationOverHTTP)client.getDestination(scheme, host, port);
        DuplexConnectionPool connectionPool = (DuplexConnectionPool)destination.getConnectionPool();

        ContentResponse response = client.newRequest(host, port)
                .scheme(scheme)
                .header(HttpHeader.CONNECTION, HttpHeaderValue.CLOSE.asString())
                .content(new StringContentProvider("0"))
                .onRequestSuccess(request ->
                {
                    HttpConnectionOverHTTP connection = (HttpConnectionOverHTTP)connectionPool.getActiveConnections().iterator().next();
                    Assert.assertFalse(connection.getEndPoint().isOutputShutdown());
                })
                .send();

        Assert.assertEquals(HttpStatus.OK_200, response.getStatus());
        Assert.assertArrayEquals(data, response.getContent());
        Assert.assertEquals(0, connectionPool.getConnectionCount());
    }

    @Test
    public void test_ClientConnectionClose_ServerDoesNotRespond_ClientIdleTimeout() throws Exception
    {
        start(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                baseRequest.setHandled(true);
                request.startAsync();
                // Do not respond.
            }
        });

        String host = "localhost";
        int port = connector.getLocalPort();

        HttpDestinationOverHTTP destination = (HttpDestinationOverHTTP)client.getDestination(scheme, host, port);
        DuplexConnectionPool connectionPool = (DuplexConnectionPool)destination.getConnectionPool();

        CountDownLatch resultLatch = new CountDownLatch(1);
        long idleTimeout = 1000;
        client.newRequest(host, port)
                .scheme(scheme)
                .header(HttpHeader.CONNECTION, HttpHeaderValue.CLOSE.asString())
                .idleTimeout(idleTimeout, TimeUnit.MILLISECONDS)
                .onRequestSuccess(request ->
                {
                    HttpConnectionOverHTTP connection = (HttpConnectionOverHTTP)connectionPool.getActiveConnections().iterator().next();
                    Assert.assertFalse(connection.getEndPoint().isOutputShutdown());
                })
                .send(result ->
                {
                    if (result.isFailed())
                        resultLatch.countDown();
                });

        Assert.assertTrue(resultLatch.await(2 * idleTimeout, TimeUnit.MILLISECONDS));
        Assert.assertEquals(0, connectionPool.getConnectionCount());
    }

    @Test
    public void test_ClientConnectionClose_ServerPartialResponse_ClientIdleTimeout() throws Exception
    {
        long idleTimeout = 1000;
        start(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
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

        HttpDestinationOverHTTP destination = (HttpDestinationOverHTTP)client.getDestination(scheme, host, port);
        DuplexConnectionPool connectionPool = (DuplexConnectionPool)destination.getConnectionPool();

        DeferredContentProvider content = new DeferredContentProvider(ByteBuffer.allocate(8));
        CountDownLatch resultLatch = new CountDownLatch(1);
        client.newRequest(host, port)
                .scheme(scheme)
                .header(HttpHeader.CONNECTION, HttpHeaderValue.CLOSE.asString())
                .content(content)
                .idleTimeout(idleTimeout, TimeUnit.MILLISECONDS)
                .onRequestSuccess(request ->
                {
                    HttpConnectionOverHTTP connection = (HttpConnectionOverHTTP)connectionPool.getActiveConnections().iterator().next();
                    Assert.assertFalse(connection.getEndPoint().isOutputShutdown());
                })
                .send(result ->
                {
                    if (result.isFailed())
                        resultLatch.countDown();
                });
        content.offer(ByteBuffer.allocate(8));
        content.close();

        Assert.assertTrue(resultLatch.await(2 * idleTimeout, TimeUnit.MILLISECONDS));
        Assert.assertEquals(0, connectionPool.getConnectionCount());
    }

    @Test
    public void test_ClientConnectionClose_ServerNoConnectionClose_ClientCloses() throws Exception
    {
        start(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
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

        HttpDestinationOverHTTP destination = (HttpDestinationOverHTTP)client.getDestination(scheme, host, port);
        DuplexConnectionPool connectionPool = (DuplexConnectionPool)destination.getConnectionPool();

        ContentResponse response = client.newRequest(host, port)
                .scheme(scheme)
                .header(HttpHeader.CONNECTION, HttpHeaderValue.CLOSE.asString())
                .onRequestSuccess(request ->
                {
                    HttpConnectionOverHTTP connection = (HttpConnectionOverHTTP)connectionPool.getActiveConnections().iterator().next();
                    Assert.assertFalse(connection.getEndPoint().isOutputShutdown());
                })
                .onResponseHeaders(r -> r.getHeaders().remove(HttpHeader.CONNECTION))
                .send();

        Assert.assertEquals(HttpStatus.OK_200, response.getStatus());
        Assert.assertEquals(0, connectionPool.getConnectionCount());
    }
}
