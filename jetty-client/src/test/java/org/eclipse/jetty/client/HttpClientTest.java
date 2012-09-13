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
import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import javax.servlet.ServletException;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.client.api.ContentProvider;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Destination;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.client.api.Result;
import org.eclipse.jetty.http.HttpCookie;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.toolchain.test.annotation.Slow;
import org.eclipse.jetty.util.IO;
import org.junit.Assert;
import org.junit.Test;

import static java.nio.file.StandardOpenOption.CREATE;

public class HttpClientTest extends AbstractHttpClientServerTest
{
    @Test
    public void testStoppingClosesConnections() throws Exception
    {
        start(new EmptyServerHandler());

        String scheme = "http";
        String host = "localhost";
        int port = connector.getLocalPort();
        String path = "/";
        Response response = client.GET(scheme + "://" + host + ":" + port + path).get(5, TimeUnit.SECONDS);
        Assert.assertEquals(200, response.status());

        HttpDestination destination = (HttpDestination)client.getDestination(scheme, host, port);

        long start = System.nanoTime();
        HttpConnection connection = null;
        while (connection == null && TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start) < 5)
        {
            connection = (HttpConnection)destination.getIdleConnections().peek();
            TimeUnit.MILLISECONDS.sleep(10);
        }
        Assert.assertNotNull(connection);

        client.getCookieStore().addCookie(destination, new HttpCookie("foo", "bar", null, path));

        client.stop();

        Assert.assertEquals(0, client.getDestinations().size());
        Assert.assertEquals(0, destination.getIdleConnections().size());
        Assert.assertEquals(0, destination.getActiveConnections().size());
        Assert.assertEquals(0, client.getCookieStore().findCookies(destination, path).size());
        Assert.assertFalse(connection.getEndPoint().isOpen());
    }

    @Test
    public void test_DestinationCount() throws Exception
    {
        start(new EmptyServerHandler());

        String scheme = "http";
        String host = "localhost";
        int port = connector.getLocalPort();
        client.GET(scheme + "://" + host + ":" + port).get(5, TimeUnit.SECONDS);

        List<Destination> destinations = client.getDestinations();
        Assert.assertNotNull(destinations);
        Assert.assertEquals(1, destinations.size());
        Destination destination = destinations.get(0);
        Assert.assertNotNull(destination);
        Assert.assertEquals(scheme, destination.scheme());
        Assert.assertEquals(host, destination.host());
        Assert.assertEquals(port, destination.port());
    }

    @Test
    public void test_GET_ResponseWithoutContent() throws Exception
    {
        start(new EmptyServerHandler());

        Response response = client.GET("http://localhost:" + connector.getLocalPort()).get(5, TimeUnit.SECONDS);

        Assert.assertNotNull(response);
        Assert.assertEquals(200, response.status());
    }

    @Test
    public void test_GET_ResponseWithContent() throws Exception
    {
        final byte[] data = new byte[]{0, 1, 2, 3, 4, 5, 6, 7};
        start(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                response.getOutputStream().write(data);
                baseRequest.setHandled(true);
            }
        });

        ContentResponse response = client.GET("http://localhost:" + connector.getLocalPort()).get(5, TimeUnit.SECONDS);

        Assert.assertNotNull(response);
        Assert.assertEquals(200, response.status());
        byte[] content = response.content();
        Assert.assertArrayEquals(data, content);
    }

    @Test
    public void test_GET_WithParameters_ResponseWithContent() throws Exception
    {
        final String paramName1 = "a";
        final String paramName2 = "b";
        start(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                response.setCharacterEncoding("UTF-8");
                ServletOutputStream output = response.getOutputStream();
                String paramValue1 = request.getParameter(paramName1);
                output.write(paramValue1.getBytes("UTF-8"));
                String paramValue2 = request.getParameter(paramName2);
                Assert.assertEquals("", paramValue2);
                output.write("empty".getBytes("UTF-8"));
                baseRequest.setHandled(true);
            }
        });

        String value1 = "\u20AC";
        String paramValue1 = URLEncoder.encode(value1, "UTF-8");
        String query = paramName1 + "=" + paramValue1 + "&" + paramName2;
        ContentResponse response = client.GET("http://localhost:" + connector.getLocalPort() + "/?" + query).get(5, TimeUnit.SECONDS);

        Assert.assertNotNull(response);
        Assert.assertEquals(200, response.status());
        String content = new String(response.content(), "UTF-8");
        Assert.assertEquals(value1 + "empty", content);
    }

    @Test
    public void test_GET_WithParametersMultiValued_ResponseWithContent() throws Exception
    {
        final String paramName1 = "a";
        final String paramName2 = "b";
        start(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                response.setCharacterEncoding("UTF-8");
                ServletOutputStream output = response.getOutputStream();
                String[] paramValues1 = request.getParameterValues(paramName1);
                for (String paramValue : paramValues1)
                    output.write(paramValue.getBytes("UTF-8"));
                String paramValue2 = request.getParameter(paramName2);
                output.write(paramValue2.getBytes("UTF-8"));
                baseRequest.setHandled(true);
            }
        });

        String value11 = "\u20AC";
        String value12 = "\u20AA";
        String value2 = "&";
        String paramValue11 = URLEncoder.encode(value11, "UTF-8");
        String paramValue12 = URLEncoder.encode(value12, "UTF-8");
        String paramValue2 = URLEncoder.encode(value2, "UTF-8");
        String query = paramName1 + "=" + paramValue11 + "&" + paramName1 + "=" + paramValue12 + "&" + paramName2 + "=" + paramValue2;
        ContentResponse response = client.GET("http://localhost:" + connector.getLocalPort() + "/?" + query).get(5, TimeUnit.SECONDS);

        Assert.assertNotNull(response);
        Assert.assertEquals(200, response.status());
        String content = new String(response.content(), "UTF-8");
        Assert.assertEquals(value11 + value12 + value2, content);
    }

    @Test
    public void test_QueuedRequest_IsSent_WhenPreviousRequestSucceeded() throws Exception
    {
        start(new EmptyServerHandler());

        client.setMaxConnectionsPerAddress(1);

        final CountDownLatch latch = new CountDownLatch(1);
        final CountDownLatch successLatch = new CountDownLatch(2);
        client.newRequest("http://localhost:" + connector.getLocalPort())
                .listener(new org.eclipse.jetty.client.api.Request.Listener.Empty()
                {
                    @Override
                    public void onBegin(org.eclipse.jetty.client.api.Request request)
                    {
                        try
                        {
                            latch.await();
                        }
                        catch (InterruptedException x)
                        {
                            x.printStackTrace();
                        }
                    }
                })
                .send(new Response.Listener.Empty()
                {
                    @Override
                    public void onSuccess(Response response)
                    {
                        Assert.assertEquals(200, response.status());
                        successLatch.countDown();
                    }
                });

        client.newRequest("http://localhost:" + connector.getLocalPort())
                .listener(new org.eclipse.jetty.client.api.Request.Listener.Empty()
                {
                    @Override
                    public void onQueued(org.eclipse.jetty.client.api.Request request)
                    {
                        latch.countDown();
                    }
                })
                .send(new Response.Listener.Empty()
                {
                    @Override
                    public void onSuccess(Response response)
                    {
                        Assert.assertEquals(200, response.status());
                        successLatch.countDown();
                    }
                });

        Assert.assertTrue(successLatch.await(5, TimeUnit.SECONDS));
    }

    @Slow
    @Test
    public void test_QueuedRequest_IsSent_WhenPreviousRequestClosedConnection() throws Exception
    {
        start(new EmptyServerHandler());

        client.setMaxConnectionsPerAddress(1);
        final long idleTimeout = 1000;
        client.setIdleTimeout(idleTimeout);

        final CountDownLatch latch = new CountDownLatch(3);
        client.newRequest("http://localhost:" + connector.getLocalPort())
                .listener(new org.eclipse.jetty.client.api.Request.Listener.Empty()
                {
                    @Override
                    public void onBegin(org.eclipse.jetty.client.api.Request request)
                    {
                        try
                        {
                            TimeUnit.MILLISECONDS.sleep(2 * idleTimeout);
                        }
                        catch (InterruptedException x)
                        {
                            x.printStackTrace();
                        }
                    }

                    @Override
                    public void onFailure(org.eclipse.jetty.client.api.Request request, Throwable failure)
                    {
                        latch.countDown();
                    }
                })
                .send(new Response.Listener.Empty()
                {
                    @Override
                    public void onFailure(Response response, Throwable failure)
                    {
                        latch.countDown();
                    }
                });

        client.newRequest("http://localhost:" + connector.getLocalPort())
                .send(new Response.Listener.Empty()
                {
                    @Override
                    public void onSuccess(Response response)
                    {
                        Assert.assertEquals(200, response.status());
                        latch.countDown();
                    }
                });

        Assert.assertTrue(latch.await(5 * idleTimeout, TimeUnit.MILLISECONDS));
    }

    @Slow
    @Test
    public void test_ExchangeIsComplete_OnlyWhenBothRequestAndResponseAreComplete() throws Exception
    {
        start(new EmptyServerHandler());

        // Prepare a big file to upload
        Path targetTestsDir = MavenTestingUtils.getTargetTestingDir().toPath();
        Files.createDirectories(targetTestsDir);
        Path file = Paths.get(targetTestsDir.toString(), "http_client_conversation.big");
        try (OutputStream output = Files.newOutputStream(file, CREATE))
        {
            byte[] kb = new byte[1024];
            for (int i = 0; i < 10 * 1024; ++i)
                output.write(kb);
        }

        final CountDownLatch latch = new CountDownLatch(3);
        final AtomicLong exchangeTime = new AtomicLong();
        final AtomicLong requestTime = new AtomicLong();
        final AtomicLong responseTime = new AtomicLong();
        client.newRequest("localhost", connector.getLocalPort())
                .file(file)
                .listener(new org.eclipse.jetty.client.api.Request.Listener.Empty()
                {
                    @Override
                    public void onSuccess(org.eclipse.jetty.client.api.Request request)
                    {
                        requestTime.set(System.nanoTime());
                        latch.countDown();
                    }
                })
                .send(new Response.Listener.Empty()
                {
                    @Override
                    public void onSuccess(Response response)
                    {
                        responseTime.set(System.nanoTime());
                        latch.countDown();
                    }

                    @Override
                    public void onComplete(Result result)
                    {
                        exchangeTime.set(System.nanoTime());
                        latch.countDown();
                    }
                });

        Assert.assertTrue(latch.await(10, TimeUnit.SECONDS));

        Assert.assertTrue(requestTime.get() <= exchangeTime.get());
        Assert.assertTrue(responseTime.get() <= exchangeTime.get());

        // Give some time to the server to consume the request content
        // This is just to avoid exception traces in the test output
        Thread.sleep(1000);

        Files.delete(file);
    }

    @Test
    public void test_ExchangeIsComplete_WhenRequestFailsMidway_WithResponse() throws Exception
    {
        final int chunkSize = 16;
        start(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                // Echo back
                IO.copy(request.getInputStream(), response.getOutputStream());
            }
        });

        final CountDownLatch latch = new CountDownLatch(1);
        client.newRequest("localhost", connector.getLocalPort())
                // The second ByteBuffer set to null will throw an exception
                .content(new ContentProvider()
                {
                    @Override
                    public long length()
                    {
                        return -1;
                    }

                    @Override
                    public Iterator<ByteBuffer> iterator()
                    {
                        return Arrays.asList(ByteBuffer.allocate(chunkSize), null).iterator();
                    }
                })
                .send(new Response.Listener.Empty()
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
                .listener(new org.eclipse.jetty.client.api.Request.Listener.Empty()
                {
                    @Override
                    public void onBegin(org.eclipse.jetty.client.api.Request request)
                    {
                        HttpDestination destination = (HttpDestination)client.getDestination("http", host, port);
                        destination.getActiveConnections().peek().close();
                    }
                })
                .send(new Response.Listener.Empty()
                {
                    @Override
                    public void onComplete(Result result)
                    {
                        latch.countDown();
                    }
                });

        Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));
    }
}
