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

import static org.junit.Assert.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpCookie;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Exchanger;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import javax.servlet.ServletException;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.client.api.Connection;
import org.eclipse.jetty.client.api.ContentProvider;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Destination;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.client.api.Result;
import org.eclipse.jetty.client.http.HttpClientTransportOverHTTP;
import org.eclipse.jetty.client.http.HttpConnectionOverHTTP;
import org.eclipse.jetty.client.http.HttpDestinationOverHTTP;
import org.eclipse.jetty.client.util.BufferingResponseListener;
import org.eclipse.jetty.client.util.BytesContentProvider;
import org.eclipse.jetty.client.util.DeferredContentProvider;
import org.eclipse.jetty.client.util.FutureResponseListener;
import org.eclipse.jetty.client.util.StringContentProvider;
import org.eclipse.jetty.http.BadMessageException;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpHeaderValue;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.io.AbstractConnection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.toolchain.test.TestingDir;
import org.eclipse.jetty.toolchain.test.annotation.Slow;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.FuturePromise;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.SocketAddressResolver;
import org.eclipse.jetty.util.log.StacklessLogging;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.hamcrest.Matchers;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;

public class HttpClientTest extends AbstractHttpClientServerTest
{
    @Rule
    public TestingDir testdir = new TestingDir();

    public HttpClientTest(SslContextFactory sslContextFactory)
    {
        super(sslContextFactory);
    }

    @Test
    public void testStoppingClosesConnections() throws Exception
    {
        start(new EmptyServerHandler());

        String host = "localhost";
        int port = connector.getLocalPort();
        String path = "/";
        Response response = client.GET(scheme + "://" + host + ":" + port + path);
        Assert.assertEquals(200, response.getStatus());

        HttpDestinationOverHTTP destination = (HttpDestinationOverHTTP)client.getDestination(scheme, host, port);
        DuplexConnectionPool connectionPool = (DuplexConnectionPool)destination.getConnectionPool();

        long start = System.nanoTime();
        HttpConnectionOverHTTP connection = null;
        while (connection == null && TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start) < 5)
        {
            connection = (HttpConnectionOverHTTP)connectionPool.getIdleConnections().peek();
            TimeUnit.MILLISECONDS.sleep(10);
        }
        Assert.assertNotNull(connection);

        String uri = destination.getScheme() + "://" + destination.getHost() + ":" + destination.getPort();
        client.getCookieStore().add(URI.create(uri), new HttpCookie("foo", "bar"));

        client.stop();

        Assert.assertEquals(0, client.getDestinations().size());
        Assert.assertEquals(0, connectionPool.getIdleConnectionCount());
        Assert.assertEquals(0, connectionPool.getActiveConnectionCount());
        Assert.assertFalse(connection.getEndPoint().isOpen());
    }

    @Test
    public void test_DestinationCount() throws Exception
    {
        start(new EmptyServerHandler());

        String host = "localhost";
        int port = connector.getLocalPort();
        client.GET(scheme + "://" + host + ":" + port);

        List<Destination> destinations = client.getDestinations();
        Assert.assertNotNull(destinations);
        Assert.assertEquals(1, destinations.size());
        Destination destination = destinations.get(0);
        Assert.assertNotNull(destination);
        Assert.assertEquals(scheme, destination.getScheme());
        Assert.assertEquals(host, destination.getHost());
        Assert.assertEquals(port, destination.getPort());
    }

    @Test
    public void test_GET_ResponseWithoutContent() throws Exception
    {
        start(new EmptyServerHandler());

        Response response = client.GET(scheme + "://localhost:" + connector.getLocalPort());

        Assert.assertNotNull(response);
        Assert.assertEquals(200, response.getStatus());
    }

    @Test
    public void test_GET_ResponseWithContent() throws Exception
    {
        final byte[] data = new byte[]{0, 1, 2, 3, 4, 5, 6, 7};
        start(new AbstractHandler()
        {
            @Override
            public void handle(String target, org.eclipse.jetty.server.Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                response.getOutputStream().write(data);
                baseRequest.setHandled(true);
            }
        });

        client.setConnectBlocking(true);
        ContentResponse response = client.GET(scheme + "://localhost:" + connector.getLocalPort());

        Assert.assertNotNull(response);
        Assert.assertEquals(200, response.getStatus());
        byte[] content = response.getContent();
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
            public void handle(String target, org.eclipse.jetty.server.Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                response.setCharacterEncoding("UTF-8");
                ServletOutputStream output = response.getOutputStream();
                String paramValue1 = request.getParameter(paramName1);
                output.write(paramValue1.getBytes(StandardCharsets.UTF_8));
                String paramValue2 = request.getParameter(paramName2);
                Assert.assertEquals("", paramValue2);
                output.write("empty".getBytes(StandardCharsets.UTF_8));
                baseRequest.setHandled(true);
            }
        });

        String value1 = "\u20AC";
        String paramValue1 = URLEncoder.encode(value1, "UTF-8");
        String query = paramName1 + "=" + paramValue1 + "&" + paramName2;
        ContentResponse response = client.GET(scheme + "://localhost:" + connector.getLocalPort() + "/?" + query);

        Assert.assertNotNull(response);
        Assert.assertEquals(200, response.getStatus());
        String content = new String(response.getContent(), StandardCharsets.UTF_8);
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
            public void handle(String target, org.eclipse.jetty.server.Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                response.setCharacterEncoding("UTF-8");
                ServletOutputStream output = response.getOutputStream();
                String[] paramValues1 = request.getParameterValues(paramName1);
                for (String paramValue : paramValues1)
                    output.write(paramValue.getBytes(StandardCharsets.UTF_8));
                String paramValue2 = request.getParameter(paramName2);
                output.write(paramValue2.getBytes(StandardCharsets.UTF_8));
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
        ContentResponse response = client.GET(scheme + "://localhost:" + connector.getLocalPort() + "/?" + query);

        Assert.assertNotNull(response);
        Assert.assertEquals(200, response.getStatus());
        String content = new String(response.getContent(), StandardCharsets.UTF_8);
        Assert.assertEquals(value11 + value12 + value2, content);
    }

    @Test
    public void test_POST_WithParameters() throws Exception
    {
        final String paramName = "a";
        final String paramValue = "\u20AC";
        start(new AbstractHandler()
        {
            @Override
            public void handle(String target, org.eclipse.jetty.server.Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                baseRequest.setHandled(true);
                String value = request.getParameter(paramName);
                if (paramValue.equals(value))
                {
                    response.setCharacterEncoding("UTF-8");
                    response.setContentType("text/plain");
                    response.getOutputStream().print(value);
                }
            }
        });

        ContentResponse response = client.POST(scheme + "://localhost:" + connector.getLocalPort())
                .param(paramName, paramValue)
                .timeout(5, TimeUnit.SECONDS)
                .send();

        Assert.assertNotNull(response);
        Assert.assertEquals(200, response.getStatus());
        Assert.assertEquals(paramValue, new String(response.getContent(), StandardCharsets.UTF_8));
    }

    @Test
    public void test_PUT_WithParameters() throws Exception
    {
        final String paramName = "a";
        final String paramValue = "\u20AC";
        final String encodedParamValue = URLEncoder.encode(paramValue, "UTF-8");
        start(new AbstractHandler()
        {
            @Override
            public void handle(String target, org.eclipse.jetty.server.Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                baseRequest.setHandled(true);
                String value = request.getParameter(paramName);
                if (paramValue.equals(value))
                {
                    response.setCharacterEncoding("UTF-8");
                    response.setContentType("text/plain");
                    response.getOutputStream().print(value);
                }
            }
        });

        URI uri = URI.create(scheme + "://localhost:" + connector.getLocalPort() + "/path?" + paramName + "=" + encodedParamValue);
        ContentResponse response = client.newRequest(uri)
                .method(HttpMethod.PUT)
                .timeout(5, TimeUnit.SECONDS)
                .send();

        Assert.assertNotNull(response);
        Assert.assertEquals(200, response.getStatus());
        Assert.assertEquals(paramValue, new String(response.getContent(), StandardCharsets.UTF_8));
    }

    @Test
    public void test_POST_WithParameters_WithContent() throws Exception
    {
        final byte[] content = {0, 1, 2, 3};
        final String paramName = "a";
        final String paramValue = "\u20AC";
        start(new AbstractHandler()
        {
            @Override
            public void handle(String target, org.eclipse.jetty.server.Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                baseRequest.setHandled(true);
                consume(request.getInputStream(), true);
                String value = request.getParameter(paramName);
                if (paramValue.equals(value))
                {
                    response.setCharacterEncoding("UTF-8");
                    response.setContentType("text/plain");
                    response.getOutputStream().write(content);
                }
            }
        });

        ContentResponse response = client.POST(scheme + "://localhost:" + connector.getLocalPort() + "/?b=1")
                .param(paramName, paramValue)
                .content(new BytesContentProvider(content))
                .timeout(5, TimeUnit.SECONDS)
                .send();

        Assert.assertNotNull(response);
        Assert.assertEquals(200, response.getStatus());
        Assert.assertArrayEquals(content, response.getContent());
    }

    @Test
    public void test_POST_WithContent_NotifiesRequestContentListener() throws Exception
    {
        start(new AbstractHandler()
        {
            @Override
            public void handle(String target, org.eclipse.jetty.server.Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                baseRequest.setHandled(true);
                consume(request.getInputStream(), true);
            }
        });

        final byte[] content = {0, 1, 2, 3};
        ContentResponse response = client.POST(scheme + "://localhost:" + connector.getLocalPort())
                .onRequestContent((request, buffer) ->
                {
                    byte[] bytes = new byte[buffer.remaining()];
                    buffer.get(bytes);
                    if (!Arrays.equals(content, bytes))
                        request.abort(new Exception());
                })
                .content(new BytesContentProvider(content))
                .timeout(5, TimeUnit.SECONDS)
                .send();

        Assert.assertNotNull(response);
        Assert.assertEquals(200, response.getStatus());
    }

    @Test
    public void test_POST_WithContent_TracksProgress() throws Exception
    {
        start(new AbstractHandler()
        {
            @Override
            public void handle(String target, org.eclipse.jetty.server.Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                baseRequest.setHandled(true);
                consume(request.getInputStream(), true);
            }
        });

        final AtomicInteger progress = new AtomicInteger();
        ContentResponse response = client.POST(scheme + "://localhost:" + connector.getLocalPort())
                .onRequestContent((request, buffer) ->
                {
                    byte[] bytes = new byte[buffer.remaining()];
                    Assert.assertEquals(1, bytes.length);
                    buffer.get(bytes);
                    Assert.assertEquals(bytes[0], progress.getAndIncrement());
                })
                .content(new BytesContentProvider(new byte[]{0}, new byte[]{1}, new byte[]{2}, new byte[]{3}, new byte[]{4}))
                .timeout(5, TimeUnit.SECONDS)
                .send();

        Assert.assertNotNull(response);
        Assert.assertEquals(200, response.getStatus());
        Assert.assertEquals(5, progress.get());
    }

    @Test
    public void test_QueuedRequest_IsSent_WhenPreviousRequestSucceeded() throws Exception
    {
        start(new EmptyServerHandler());

        client.setMaxConnectionsPerDestination(1);

        final CountDownLatch latch = new CountDownLatch(1);
        final CountDownLatch successLatch = new CountDownLatch(2);
        client.newRequest("localhost", connector.getLocalPort())
                .scheme(scheme)
                .onRequestBegin(request ->
                {
                    try
                    {
                        latch.await();
                    }
                    catch (InterruptedException x)
                    {
                        x.printStackTrace();
                    }
                })
                .send(new Response.Listener.Adapter()
                {
                    @Override
                    public void onSuccess(Response response)
                    {
                        Assert.assertEquals(200, response.getStatus());
                        successLatch.countDown();
                    }
                });

        client.newRequest("localhost", connector.getLocalPort())
                .scheme(scheme)
                .onRequestQueued(request -> latch.countDown())
                .send(new Response.Listener.Adapter()
                {
                    @Override
                    public void onSuccess(Response response)
                    {
                        Assert.assertEquals(200, response.getStatus());
                        successLatch.countDown();
                    }
                });

        Assert.assertTrue(successLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void test_QueuedRequest_IsSent_WhenPreviousRequestClosedConnection() throws Exception
    {
        start(new AbstractHandler()
        {
            @Override
            public void handle(String target, org.eclipse.jetty.server.Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                if (target.endsWith("/one"))
                    baseRequest.getHttpChannel().getEndPoint().close();
                else
                    baseRequest.setHandled(true);
            }
        });

        client.setMaxConnectionsPerDestination(1);

        try (StacklessLogging stackless = new StacklessLogging(org.eclipse.jetty.server.HttpChannel.class))
        {
            final CountDownLatch latch = new CountDownLatch(2);
            client.newRequest("localhost", connector.getLocalPort())
            .scheme(scheme)
            .path("/one")
            .onResponseFailure((response, failure) -> latch.countDown())
            .send(null);

            client.newRequest("localhost", connector.getLocalPort())
            .scheme(scheme)
            .path("/two")
            .onResponseSuccess(response ->
            {
                Assert.assertEquals(200, response.getStatus());
                latch.countDown();
            })
            .send(null);

            Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));
        }
    }

    @Test
    public void test_ExchangeIsComplete_OnlyWhenBothRequestAndResponseAreComplete() throws Exception
    {
        start(new RespondThenConsumeHandler());

        // Prepare a big file to upload
        Path targetTestsDir = testdir.getEmptyPathDir();
        Files.createDirectories(targetTestsDir);
        Path file = Paths.get(targetTestsDir.toString(), "http_client_conversation.big");
        try (OutputStream output = Files.newOutputStream(file, StandardOpenOption.CREATE))
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
                .scheme(scheme)
                .file(file)
                .onRequestSuccess(request ->
                {
                    requestTime.set(System.nanoTime());
                    latch.countDown();
                })
                .send(new Response.Listener.Adapter()
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
                .onRequestBegin(request ->
                {
                    HttpDestinationOverHTTP destination = (HttpDestinationOverHTTP)client.getDestination(scheme, host, port);
                    DuplexConnectionPool connectionPool = (DuplexConnectionPool)destination.getConnectionPool();
                    connectionPool.getActiveConnections().iterator().next().close();
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

    @Slow
    @Test
    public void test_Request_IdleTimeout() throws Exception
    {
        final long idleTimeout = 1000;
        start(new AbstractHandler()
        {
            @Override
            public void handle(String target, org.eclipse.jetty.server.Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                try
                {
                    baseRequest.setHandled(true);
                    TimeUnit.MILLISECONDS.sleep(2 * idleTimeout);
                }
                catch (InterruptedException x)
                {
                    throw new ServletException(x);
                }
            }
        });

        final String host = "localhost";
        final int port = connector.getLocalPort();
        try
        {
            client.newRequest(host, port)
                    .scheme(scheme)
                    .idleTimeout(idleTimeout, TimeUnit.MILLISECONDS)
                    .timeout(3 * idleTimeout, TimeUnit.MILLISECONDS)
                    .send();
            Assert.fail();
        }
        catch (ExecutionException expected)
        {
            Assert.assertTrue(expected.getCause() instanceof TimeoutException);
        }

        // Make another request without specifying the idle timeout, should not fail
        ContentResponse response = client.newRequest(host, port)
                .scheme(scheme)
                .timeout(3 * idleTimeout, TimeUnit.MILLISECONDS)
                .send();

        Assert.assertNotNull(response);
        Assert.assertEquals(200, response.getStatus());
    }

    @Test
    public void testSendToIPv6Address() throws Exception
    {
        start(new EmptyServerHandler());

        ContentResponse response = client.newRequest("[::1]", connector.getLocalPort())
                .scheme(scheme)
                .timeout(5, TimeUnit.SECONDS)
                .send();

        Assert.assertNotNull(response);
        Assert.assertEquals(200, response.getStatus());
    }

    @Test
    public void testHeaderProcessing() throws Exception
    {
        final String headerName = "X-Header-Test";
        start(new AbstractHandler()
        {
            @Override
            public void handle(String target, org.eclipse.jetty.server.Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                baseRequest.setHandled(true);
                response.setHeader(headerName, "X");
            }
        });

        ContentResponse response = client.newRequest("localhost", connector.getLocalPort())
                .scheme(scheme)
                .onResponseHeader((response1, field) -> !field.getName().equals(headerName))
                .timeout(5, TimeUnit.SECONDS)
                .send();

        Assert.assertNotNull(response);
        Assert.assertEquals(200, response.getStatus());
        Assert.assertFalse(response.getHeaders().containsKey(headerName));
    }

    @Test
    public void testAllHeadersDiscarded() throws Exception
    {
        start(new EmptyServerHandler());

        int count = 10;
        final CountDownLatch latch = new CountDownLatch(count);
        for (int i = 0; i < count; ++i)
        {
            client.newRequest("localhost", connector.getLocalPort())
                    .scheme(scheme)
                    .send(new Response.Listener.Adapter()
                    {
                        @Override
                        public boolean onHeader(Response response, HttpField field)
                        {
                            return false;
                        }

                        @Override
                        public void onComplete(Result result)
                        {
                            if (result.isSucceeded())
                                latch.countDown();
                        }
                    });
        }

        Assert.assertTrue(latch.await(10, TimeUnit.SECONDS));
    }

    @Test
    public void test_HEAD_With_ResponseContentLength() throws Exception
    {
        final int length = 1024;
        start(new AbstractHandler()
        {
            @Override
            public void handle(String target, org.eclipse.jetty.server.Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                baseRequest.setHandled(true);
                response.getOutputStream().write(new byte[length]);
            }
        });

        // HEAD requests receive a Content-Length header, but do not
        // receive the content so they must handle this case properly
        ContentResponse response = client.newRequest("localhost", connector.getLocalPort())
                .scheme(scheme)
                .method(HttpMethod.HEAD)
                .timeout(5, TimeUnit.SECONDS)
                .send();

        Assert.assertNotNull(response);
        Assert.assertEquals(200, response.getStatus());
        Assert.assertEquals(0, response.getContent().length);

        // Perform a normal GET request to be sure the content is now read
        response = client.newRequest("localhost", connector.getLocalPort())
                .scheme(scheme)
                .timeout(5, TimeUnit.SECONDS)
                .send();

        Assert.assertNotNull(response);
        Assert.assertEquals(200, response.getStatus());
        Assert.assertEquals(length, response.getContent().length);
    }

    @Test
    public void testConnectThrowsUnknownHostException() throws Exception
    {
        String host = "idontexist";
        int port = 80;

        try
        {
            Socket socket = new Socket();
            socket.connect(new InetSocketAddress(host, port), 1000);
            Assume.assumeTrue("Host must not be resolvable", false);
        }
        catch (IOException ignored)
        {
        }

        start(new EmptyServerHandler());

        final CountDownLatch latch = new CountDownLatch(1);
        client.newRequest(host, port)
                .send(result ->
                {
                    Assert.assertTrue(result.isFailed());
                    Throwable failure = result.getFailure();
                    Assert.assertTrue(failure instanceof UnknownHostException);
                    latch.countDown();
                });
        Assert.assertTrue(latch.await(10, TimeUnit.SECONDS));
    }

    @Test
    public void testConnectHostWithMultipleAddresses() throws Exception
    {
        start(new EmptyServerHandler());

        client.setSocketAddressResolver(new SocketAddressResolver.Async(client.getExecutor(), client.getScheduler(), client.getConnectTimeout())
        {
            @Override
            public void resolve(String host, int port, Promise<List<InetSocketAddress>> promise)
            {
                super.resolve(host, port, new Promise<List<InetSocketAddress>>()
                {
                    @Override
                    public void succeeded(List<InetSocketAddress> result)
                    {
                        // Add as first address an invalid address so that we test
                        // that the connect operation iterates over the addresses.
                        result.add(0, new InetSocketAddress("idontexist", port));
                        promise.succeeded(result);
                    }

                    @Override
                    public void failed(Throwable x)
                    {
                        promise.failed(x);
                    }
                });
            }
        });

        // If no exceptions the test passes.
        client.newRequest("localhost", connector.getLocalPort())
                .scheme(scheme)
                .header(HttpHeader.CONNECTION, "close")
                .send();
    }

    @Test
    public void testCustomUserAgent() throws Exception
    {
        final String userAgent = "Test/1.0";
        start(new AbstractHandler()
        {
            @Override
            public void handle(String target, org.eclipse.jetty.server.Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                baseRequest.setHandled(true);
                ArrayList<String> userAgents = Collections.list(request.getHeaders("User-Agent"));
                Assert.assertEquals(1, userAgents.size());
                Assert.assertEquals(userAgent, userAgents.get(0));
            }
        });

        ContentResponse response = client.newRequest("localhost", connector.getLocalPort())
                .scheme(scheme)
                .agent(userAgent)
                .timeout(5, TimeUnit.SECONDS)
                .send();

        Assert.assertEquals(200, response.getStatus());

        response = client.newRequest("localhost", connector.getLocalPort())
                .scheme(scheme)
                .header(HttpHeader.USER_AGENT, null)
                .header(HttpHeader.USER_AGENT, userAgent)
                .timeout(5, TimeUnit.SECONDS)
                .send();

        Assert.assertEquals(200, response.getStatus());
    }

    @Test
    public void testUserAgentCanBeRemoved() throws Exception
    {
        start(new AbstractHandler()
        {
            @Override
            public void handle(String target, org.eclipse.jetty.server.Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                baseRequest.setHandled(true);
                ArrayList<String> userAgents = Collections.list(request.getHeaders("User-Agent"));
                if ("/ua".equals(target))
                    Assert.assertEquals(1, userAgents.size());
                else
                    Assert.assertEquals(0, userAgents.size());
            }
        });

        // User agent not specified, use default.
        ContentResponse response = client.newRequest("localhost", connector.getLocalPort())
                .scheme(scheme)
                .path("/ua")
                .timeout(5, TimeUnit.SECONDS)
                .send();

        Assert.assertEquals(200, response.getStatus());

        // User agent explicitly removed.
        response = client.newRequest("localhost", connector.getLocalPort())
                .scheme(scheme)
                .agent(null)
                .timeout(5, TimeUnit.SECONDS)
                .send();

        Assert.assertEquals(200, response.getStatus());

        // User agent explicitly removed.
        response = client.newRequest("localhost", connector.getLocalPort())
                .scheme(scheme)
                .header(HttpHeader.USER_AGENT, null)
                .timeout(5, TimeUnit.SECONDS)
                .send();

        Assert.assertEquals(200, response.getStatus());
    }

    @Test
    public void testRequestListenerForMultipleEventsIsInvokedOncePerEvent() throws Exception
    {
        start(new EmptyServerHandler());

        final AtomicInteger counter = new AtomicInteger();
        Request.Listener listener = new Request.Listener()
        {
            @Override
            public void onQueued(Request request)
            {
                counter.incrementAndGet();
            }

            @Override
            public void onBegin(Request request)
            {
                counter.incrementAndGet();
            }

            @Override
            public void onHeaders(Request request)
            {
                counter.incrementAndGet();
            }

            @Override
            public void onCommit(Request request)
            {
                counter.incrementAndGet();
            }

            @Override
            public void onContent(Request request, ByteBuffer content)
            {
                // Should not be invoked
                counter.incrementAndGet();
            }

            @Override
            public void onFailure(Request request, Throwable failure)
            {
                // Should not be invoked
                counter.incrementAndGet();
            }

            @Override
            public void onSuccess(Request request)
            {
                counter.incrementAndGet();
            }
        };
        ContentResponse response = client.newRequest("localhost", connector.getLocalPort())
                .scheme(scheme)
                .onRequestQueued(listener)
                .onRequestBegin(listener)
                .onRequestHeaders(listener)
                .onRequestCommit(listener)
                .onRequestContent(listener)
                .onRequestSuccess(listener)
                .onRequestFailure(listener)
                .listener(listener)
                .send();

        Assert.assertEquals(200, response.getStatus());
        int expectedEventsTriggeredByOnRequestXXXListeners = 5;
        int expectedEventsTriggeredByListener = 5;
        int expected = expectedEventsTriggeredByOnRequestXXXListeners + expectedEventsTriggeredByListener;
        Assert.assertEquals(expected, counter.get());
    }

    @Test
    public void testResponseListenerForMultipleEventsIsInvokedOncePerEvent() throws Exception
    {
        start(new EmptyServerHandler());

        final AtomicInteger counter = new AtomicInteger();
        final CountDownLatch latch = new CountDownLatch(1);
        Response.Listener listener = new Response.Listener()
        {
            @Override
            public void onBegin(Response response)
            {
                counter.incrementAndGet();
            }

            @Override
            public boolean onHeader(Response response, HttpField field)
            {
                // Number of header may vary, so don't count
                return true;
            }

            @Override
            public void onHeaders(Response response)
            {
                counter.incrementAndGet();
            }

            @Override
            public void onContent(Response response, ByteBuffer content)
            {
                // Should not be invoked
                counter.incrementAndGet();
            }

            @Override
            public void onContent(Response response, ByteBuffer content, Callback callback)
            {
                // Should not be invoked
                counter.incrementAndGet();
            }

            @Override
            public void onSuccess(Response response)
            {
                counter.incrementAndGet();
            }

            @Override
            public void onFailure(Response response, Throwable failure)
            {
                // Should not be invoked
                counter.incrementAndGet();
            }

            @Override
            public void onComplete(Result result)
            {
                Assert.assertEquals(200, result.getResponse().getStatus());
                counter.incrementAndGet();
                latch.countDown();
            }
        };
        client.newRequest("localhost", connector.getLocalPort())
                .scheme(scheme)
                .onResponseBegin(listener)
                .onResponseHeader(listener)
                .onResponseHeaders(listener)
                .onResponseContent(listener)
                .onResponseContentAsync(listener)
                .onResponseSuccess(listener)
                .onResponseFailure(listener)
                .send(listener);

        Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));
        int expectedEventsTriggeredByOnResponseXXXListeners = 3;
        int expectedEventsTriggeredByCompletionListener = 4;
        int expected = expectedEventsTriggeredByOnResponseXXXListeners + expectedEventsTriggeredByCompletionListener;
        Assert.assertEquals(expected, counter.get());
    }

    @Test
    public void setOnCompleteCallbackWithBlockingSend() throws Exception
    {
        final byte[] content = new byte[512];
        new Random().nextBytes(content);
        start(new AbstractHandler()
        {
            @Override
            public void handle(String target, org.eclipse.jetty.server.Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                baseRequest.setHandled(true);
                response.getOutputStream().write(content);
            }
        });

        final Exchanger<Response> ex = new Exchanger<>();
        BufferingResponseListener listener = new BufferingResponseListener()
        {
            @Override
            public void onComplete(Result result)
            {
                try
                {
                    ex.exchange(result.getResponse());
                }
                catch (InterruptedException e)
                {
                    e.printStackTrace();
                }
            }
        };


        client.newRequest("localhost", connector.getLocalPort())
                .scheme(scheme)
                .send(listener);

        Response response = ex.exchange(null);

        Assert.assertEquals(200, response.getStatus());
        Assert.assertArrayEquals(content, listener.getContent());

    }

    @Test
    public void testCustomHostHeader() throws Exception
    {
        final String host = "localhost";
        start(new AbstractHandler()
        {
            @Override
            public void handle(String target, org.eclipse.jetty.server.Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                baseRequest.setHandled(true);
                Assert.assertEquals(host, request.getServerName());
            }
        });

        ContentResponse response = client.newRequest("http://127.0.0.1:" + connector.getLocalPort() + "/path")
                .scheme(scheme)
                .header(HttpHeader.HOST, host)
                .send();

        Assert.assertEquals(200, response.getStatus());
    }

    @Test
    public void testHTTP10WithKeepAliveAndContentLength() throws Exception
    {
        start(new AbstractHandler()
        {
            @Override
            public void handle(String target, org.eclipse.jetty.server.Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                // Send the headers at this point, then write the content
                byte[] content = "TEST".getBytes("UTF-8");
                response.setContentLength(content.length);
                response.flushBuffer();
                response.getOutputStream().write(content);
            }
        });

        ContentResponse response = client.newRequest("localhost", connector.getLocalPort())
                .scheme(scheme)
                .version(HttpVersion.HTTP_1_0)
                .header(HttpHeader.CONNECTION, HttpHeaderValue.KEEP_ALIVE.asString())
                .timeout(5, TimeUnit.SECONDS)
                .send();

        Assert.assertEquals(200, response.getStatus());
        Assert.assertTrue(response.getHeaders().contains(HttpHeader.CONNECTION, HttpHeaderValue.KEEP_ALIVE.asString()));
    }

    @Test
    public void testHTTP10WithKeepAliveAndNoContentLength() throws Exception
    {
        start(new AbstractHandler()
        {
            @Override
            public void handle(String target, org.eclipse.jetty.server.Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                // Send the headers at this point, then write the content
                response.flushBuffer();
                response.getOutputStream().print("TEST");
            }
        });

        FuturePromise<Connection> promise = new FuturePromise<>();
        Destination destination = client.getDestination(scheme, "localhost", connector.getLocalPort());
        destination.newConnection(promise);
        try (Connection connection = promise.get(5, TimeUnit.SECONDS))
        {
            long timeout = 5000;
            Request request = client.newRequest(destination.getHost(), destination.getPort())
                    .scheme(destination.getScheme())
                    .version(HttpVersion.HTTP_1_0)
                    .header(HttpHeader.CONNECTION, HttpHeaderValue.KEEP_ALIVE.asString())
                    .timeout(timeout, TimeUnit.MILLISECONDS);

            FutureResponseListener listener = new FutureResponseListener(request);
            connection.send(request, listener);
            ContentResponse response = listener.get(2 * timeout, TimeUnit.MILLISECONDS);

            Assert.assertEquals(200, response.getStatus());
            // The parser notifies end-of-content and therefore the CompleteListener
            // before closing the connection, so we need to wait before checking
            // that the connection is closed to avoid races.
            Thread.sleep(1000);
            Assert.assertTrue(connection.isClosed());
        }
    }

    @Test
    public void testHTTP10WithKeepAliveAndNoContent() throws Exception
    {
        start(new EmptyServerHandler());

        ContentResponse response = client.newRequest("localhost", connector.getLocalPort())
                .scheme(scheme)
                .version(HttpVersion.HTTP_1_0)
                .header(HttpHeader.CONNECTION, HttpHeaderValue.KEEP_ALIVE.asString())
                .timeout(5, TimeUnit.SECONDS)
                .send();

        Assert.assertEquals(200, response.getStatus());
        Assert.assertTrue(response.getHeaders().contains(HttpHeader.CONNECTION, HttpHeaderValue.KEEP_ALIVE.asString()));
    }

    @Test
    public void testLongPollIsAbortedWhenClientIsStopped() throws Exception
    {
        final CountDownLatch latch = new CountDownLatch(1);
        start(new AbstractHandler()
        {
            @Override
            public void handle(String target, org.eclipse.jetty.server.Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                baseRequest.setHandled(true);
                request.startAsync();
                latch.countDown();
            }
        });

        final CountDownLatch completeLatch = new CountDownLatch(1);
        client.newRequest("localhost", connector.getLocalPort())
                .scheme(scheme)
                .send(result ->
                {
                    if (result.isFailed())
                        completeLatch.countDown();
                });

        Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));

        // Stop the client, the complete listener must be invoked.
        client.stop();

        Assert.assertTrue(completeLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testSmallContentDelimitedByEOFWithSlowRequestHTTP10() throws Exception
    {
        try
        {
            testContentDelimitedByEOFWithSlowRequest(HttpVersion.HTTP_1_0, 1024);
        }
        catch(ExecutionException e)
        {
            assertThat(e.getCause(), Matchers.instanceOf(BadMessageException.class));
            assertThat(e.getCause().getMessage(), Matchers.containsString("Unknown content"));
        }
    }

    @Test
    public void testBigContentDelimitedByEOFWithSlowRequestHTTP10() throws Exception
    {
        try
        {
            testContentDelimitedByEOFWithSlowRequest(HttpVersion.HTTP_1_0, 128 * 1024);
        }
        catch(ExecutionException e)
        {
            assertThat(e.getCause(), Matchers.instanceOf(BadMessageException.class));
            assertThat(e.getCause().getMessage(), Matchers.containsString("Unknown content"));
        }
    }

    @Test
    public void testSmallContentDelimitedByEOFWithSlowRequestHTTP11() throws Exception
    {
        testContentDelimitedByEOFWithSlowRequest(HttpVersion.HTTP_1_1, 1024);
    }

    @Test
    public void testBigContentDelimitedByEOFWithSlowRequestHTTP11() throws Exception
    {
        testContentDelimitedByEOFWithSlowRequest(HttpVersion.HTTP_1_1, 128 * 1024);
    }

    private void testContentDelimitedByEOFWithSlowRequest(final HttpVersion version, int length) throws Exception
    {
        // This test is crafted in a way that the response completes before the request is fully written.
        // With SSL, the response coming down will close the SSLEngine so it would not be possible to
        // write the last chunk of the request content, and the request will be failed, failing also the
        // test, which is not what we want.
        // This is a limit of Java's SSL implementation that does not allow half closes.
        Assume.assumeTrue(sslContextFactory == null);

        final byte[] data = new byte[length];
        new Random().nextBytes(data);
        start(new AbstractHandler()
        {
            @Override
            public void handle(String target, org.eclipse.jetty.server.Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                baseRequest.setHandled(true);
                // Send Connection: close to avoid that the server chunks the content with HTTP 1.1.
                if (version.compareTo(HttpVersion.HTTP_1_0) > 0)
                    response.setHeader("Connection", "close");
                response.getOutputStream().write(data);
            }
        });

        DeferredContentProvider content = new DeferredContentProvider(ByteBuffer.wrap(new byte[]{0}));
        Request request = client.newRequest("localhost", connector.getLocalPort())
                .scheme(scheme)
                .version(version)
                .content(content);
        FutureResponseListener listener = new FutureResponseListener(request);
        request.send(listener);
        // Wait some time to simulate a slow request.
        Thread.sleep(1000);
        content.close();

        ContentResponse response = listener.get(5, TimeUnit.SECONDS);

        Assert.assertEquals(200, response.getStatus());
        Assert.assertArrayEquals(data, response.getContent());
    }

    @Test
    public void testRequestRetries() throws Exception
    {
        final int maxRetries = 3;
        final AtomicInteger requests = new AtomicInteger();
        start(new AbstractHandler()
        {
            @Override
            public void handle(String target, org.eclipse.jetty.server.Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                int count = requests.incrementAndGet();
                if (count == maxRetries)
                    baseRequest.setHandled(true);
                consume(request.getInputStream(), true);
            }
        });

        final CountDownLatch latch = new CountDownLatch(1);
        new RetryListener(client, scheme, "localhost", connector.getLocalPort(), maxRetries)
        {
            @Override
            protected void completed(Result result)
            {
                latch.countDown();
            }
        }.perform();

        Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testCompleteNotInvokedUntilContentConsumed() throws Exception
    {
        start(new AbstractHandler()
        {
            @Override
            public void handle(String target, org.eclipse.jetty.server.Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                baseRequest.setHandled(true);
                ServletOutputStream output = response.getOutputStream();
                output.write(new byte[1024]);
            }
        });

        final AtomicReference<Callback> callbackRef = new AtomicReference<>();
        final CountDownLatch contentLatch = new CountDownLatch(1);
        final CountDownLatch completeLatch = new CountDownLatch(1);
        client.newRequest("localhost", connector.getLocalPort())
                .scheme(scheme)
                .send(new Response.Listener.Adapter()
                {
                    @Override
                    public void onContent(Response response, ByteBuffer content, Callback callback)
                    {
                        // Do not notify the callback yet.
                        callbackRef.set(callback);
                        contentLatch.countDown();
                    }

                    @Override
                    public void onComplete(Result result)
                    {
                        if (result.isSucceeded())
                            completeLatch.countDown();
                    }
                });

        Assert.assertTrue(contentLatch.await(5, TimeUnit.SECONDS));

        // Make sure the complete event is not emitted.
        Assert.assertFalse(completeLatch.await(1, TimeUnit.SECONDS));

        // Consume the content.
        callbackRef.get().succeeded();

        // Now the complete event is emitted.
        Assert.assertTrue(completeLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testRequestSentOnlyAfterConnectionOpen() throws Exception
    {
        startServer(new AbstractHandler()
        {
            @Override
            public void handle(String target, org.eclipse.jetty.server.Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                baseRequest.setHandled(true);
            }
        });

        final AtomicBoolean open = new AtomicBoolean();
        client = new HttpClient(new HttpClientTransportOverHTTP()
        {
            @Override
            protected HttpConnectionOverHTTP newHttpConnection(EndPoint endPoint, HttpDestination destination, Promise<Connection> promise)
            {
                return new HttpConnectionOverHTTP(endPoint, destination, promise)
                {
                    @Override
                    public void onOpen()
                    {
                        open.set(true);
                        super.onOpen();
                    }
                };
            }
        }, sslContextFactory);
        client.start();

        final CountDownLatch latch = new CountDownLatch(2);
        client.newRequest("localhost", connector.getLocalPort())
                .scheme(scheme)
                .onRequestBegin(request ->
                {
                    Assert.assertTrue(open.get());
                    latch.countDown();
                })
                .send(result ->
                {
                    if (result.isSucceeded())
                        latch.countDown();
                });

        Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testCONNECTWithHTTP10() throws Exception
    {
        try (ServerSocket server = new ServerSocket(0))
        {
            startClient();

            String host = "localhost";
            int port = server.getLocalPort();

            Request request = client.newRequest(host, port)
                    .method(HttpMethod.CONNECT)
                    .version(HttpVersion.HTTP_1_0);
            FuturePromise<Connection> promise = new FuturePromise<>();
            client.getDestination("http", host, port).newConnection(promise);
            Connection connection = promise.get(5, TimeUnit.SECONDS);
            FutureResponseListener listener = new FutureResponseListener(request);
            connection.send(request, listener);

            try (Socket socket = server.accept())
            {
                InputStream input = socket.getInputStream();
                consume(input, false);

                // HTTP/1.0 response, the client must not close the connection.
                String httpResponse = "" +
                        "HTTP/1.0 200 OK\r\n" +
                        "\r\n";
                OutputStream output = socket.getOutputStream();
                output.write(httpResponse.getBytes(StandardCharsets.UTF_8));
                output.flush();

                ContentResponse response = listener.get(5, TimeUnit.SECONDS);
                Assert.assertEquals(200, response.getStatus());

                // Because the tunnel was successful, this connection will be
                // upgraded to an SslConnection, so it will not be fill interested.
                // This test doesn't upgrade, so it needs to restore the fill interest.
                ((AbstractConnection)connection).fillInterested();

                // Test that I can send another request on the same connection.
                request = client.newRequest(host, port);
                listener = new FutureResponseListener(request);
                connection.send(request, listener);

                consume(input, false);

                httpResponse = "" +
                        "HTTP/1.1 200 OK\r\n" +
                        "Content-Length: 0\r\n" +
                        "\r\n";
                output.write(httpResponse.getBytes(StandardCharsets.UTF_8));
                output.flush();

                listener.get(5, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    public void test_IPv6_Host() throws Exception
    {
        start(new AbstractHandler()
        {
            @Override
            public void handle(String target, org.eclipse.jetty.server.Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                baseRequest.setHandled(true);
                response.setContentType("text/plain");
                response.getOutputStream().print(request.getHeader("Host"));
            }
        });

        URI uri = URI.create(scheme + "://[::1]:" + connector.getLocalPort() + "/path");
        ContentResponse response = client.newRequest(uri)
            .method(HttpMethod.PUT)
            .timeout(5, TimeUnit.SECONDS)
            .send();

        Assert.assertNotNull(response);
        Assert.assertEquals(200, response.getStatus());
        Assert.assertThat(new String(response.getContent(), StandardCharsets.ISO_8859_1),Matchers.startsWith("[::1]:"));
    }

    @Test
    public void testCopyRequest() throws Exception
    {
        startClient();

        assertCopyRequest(client.newRequest("http://example.com/some/url")
                .method(HttpMethod.HEAD)
                .version(HttpVersion.HTTP_2)
                .content(new StringContentProvider("some string"))
                .timeout(321, TimeUnit.SECONDS)
                .idleTimeout(2221, TimeUnit.SECONDS)
                .followRedirects(true)
                .header(HttpHeader.CONTENT_TYPE, "application/json")
                .header("X-Some-Custom-Header", "some-value"));

        assertCopyRequest(client.newRequest("https://example.com")
                .method(HttpMethod.POST)
                .version(HttpVersion.HTTP_1_0)
                .content(new StringContentProvider("some other string"))
                .timeout(123231, TimeUnit.SECONDS)
                .idleTimeout(232342, TimeUnit.SECONDS)
                .followRedirects(false)
                .header(HttpHeader.ACCEPT, "application/json")
                .header("X-Some-Other-Custom-Header", "some-other-value"));

        assertCopyRequest(client.newRequest("https://example.com")
                .header(HttpHeader.ACCEPT, "application/json")
                .header(HttpHeader.ACCEPT, "application/xml")
                .header("x-same-name", "value1")
                .header("x-same-name", "value2"));

        assertCopyRequest(client.newRequest("https://example.com")
                .header(HttpHeader.ACCEPT, "application/json")
                .header(HttpHeader.CONTENT_TYPE, "application/json"));

        assertCopyRequest(client.newRequest("https://example.com")
                .header("Accept", "application/json")
                .header("Content-Type", "application/json"));

        assertCopyRequest(client.newRequest("https://example.com")
                .header("X-Custom-Header-1", "value1")
                .header("X-Custom-Header-2", "value2"));

        assertCopyRequest(client.newRequest("https://example.com")
                .header("X-Custom-Header-1", "value")
                .header("X-Custom-Header-2", "value"));
    }

    @Test
    public void testHostWithHTTP10() throws Exception
    {
        start(new AbstractHandler()
        {
            @Override
            public void handle(String target, org.eclipse.jetty.server.Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                baseRequest.setHandled(true);
                Assert.assertThat(request.getHeader("Host"), Matchers.notNullValue());
            }
        });

        ContentResponse response = client.newRequest("localhost", connector.getLocalPort())
                .scheme(scheme)
                .version(HttpVersion.HTTP_1_0)
                .timeout(5, TimeUnit.SECONDS)
                .send();

        Assert.assertEquals(200, response.getStatus());
    }

    private void assertCopyRequest(Request original)
    {
        Request copy = client.copyRequest((HttpRequest) original, original.getURI());
        Assert.assertEquals(original.getURI(), copy.getURI());
        Assert.assertEquals(original.getMethod(), copy.getMethod());
        Assert.assertEquals(original.getVersion(), copy.getVersion());
        Assert.assertEquals(original.getContent(), copy.getContent());
        Assert.assertEquals(original.getIdleTimeout(), copy.getIdleTimeout());
        Assert.assertEquals(original.getTimeout(), copy.getTimeout());
        Assert.assertEquals(original.isFollowRedirects(), copy.isFollowRedirects());
        Assert.assertEquals(original.getHeaders(), copy.getHeaders());
    }

    private void consume(InputStream input, boolean eof) throws IOException
    {
        int crlfs = 0;
        while (true)
        {
            int read = input.read();
            if (read == '\r' || read == '\n')
                ++crlfs;
            else
                crlfs = 0;
            if (!eof && crlfs == 4)
                break;
            if (read < 0)
                break;
        }
    }

    public static abstract class RetryListener implements Response.CompleteListener
    {
        private final HttpClient client;
        private final String scheme;
        private final String host;
        private final int port;
        private final int maxRetries;
        private int retries;

        public RetryListener(HttpClient client, String scheme, String host, int port, int maxRetries)
        {
            this.client = client;
            this.scheme = scheme;
            this.host = host;
            this.port = port;
            this.maxRetries = maxRetries;
        }

        protected abstract void completed(Result result);

        @Override
        public void onComplete(Result result)
        {
            if (retries > maxRetries || result.isSucceeded() && result.getResponse().getStatus() == 200)
                completed(result);
            else
                retry();
        }

        private void retry()
        {
            ++retries;
            perform();
        }

        public void perform()
        {
            client.newRequest(host, port)
                    .scheme(scheme)
                    .method("POST")
                    .param("attempt", String.valueOf(retries))
                    .content(new StringContentProvider("0123456789ABCDEF"))
                    .send(this);
        }
    }
}
