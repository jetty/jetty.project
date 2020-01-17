//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.client;

import java.io.ByteArrayOutputStream;
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
import java.util.function.LongConsumer;
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
import org.eclipse.jetty.http.HttpScheme;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.io.AbstractConnection;
import org.eclipse.jetty.io.ClientConnector;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.toolchain.test.Net;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.FuturePromise;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.SocketAddressResolver;
import org.eclipse.jetty.util.log.StacklessLogging;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.condition.DisabledIfSystemProperty;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.instanceOf;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

// @checkstyle-disable-check : AvoidEscapedUnicodeCharactersCheck
@ExtendWith(WorkDirExtension.class)
public class HttpClientTest extends AbstractHttpClientServerTest
{
    public WorkDir testdir;

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testStoppingClosesConnections(Scenario scenario) throws Exception
    {
        start(scenario, new EmptyServerHandler());

        String host = "localhost";
        int port = connector.getLocalPort();
        String path = "/";
        Request request = client.newRequest(scenario.getScheme() + "://" + host + ":" + port + path);
        Response response = request.send();
        assertEquals(200, response.getStatus());

        HttpDestination destination = (HttpDestination)client.resolveDestination(request);
        DuplexConnectionPool connectionPool = (DuplexConnectionPool)destination.getConnectionPool();

        long start = System.nanoTime();
        HttpConnectionOverHTTP connection = null;
        while (connection == null && TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start) < 5)
        {
            connection = (HttpConnectionOverHTTP)connectionPool.getIdleConnections().peek();
            TimeUnit.MILLISECONDS.sleep(10);
        }
        assertNotNull(connection);

        String uri = destination.getScheme() + "://" + destination.getHost() + ":" + destination.getPort();
        client.getCookieStore().add(URI.create(uri), new HttpCookie("foo", "bar"));

        client.stop();

        assertEquals(0, client.getDestinations().size());
        assertEquals(0, connectionPool.getIdleConnectionCount());
        assertEquals(0, connectionPool.getActiveConnectionCount());
        assertFalse(connection.getEndPoint().isOpen());
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testDestinationCount(Scenario scenario) throws Exception
    {
        start(scenario, new EmptyServerHandler());

        String host = "localhost";
        int port = connector.getLocalPort();
        client.GET(scenario.getScheme() + "://" + host + ":" + port);

        List<Destination> destinations = client.getDestinations();
        assertNotNull(destinations);
        assertEquals(1, destinations.size());
        Destination destination = destinations.get(0);
        assertNotNull(destination);
        assertEquals(scenario.getScheme(), destination.getScheme());
        assertEquals(host, destination.getHost());
        assertEquals(port, destination.getPort());
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testGETResponseWithoutContent(Scenario scenario) throws Exception
    {
        start(scenario, new EmptyServerHandler());

        Response response = client.GET(scenario.getScheme() + "://localhost:" + connector.getLocalPort());

        assertNotNull(response);
        assertEquals(200, response.getStatus());
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testGETResponseWithContent(Scenario scenario) throws Exception
    {
        byte[] data = new byte[]{0, 1, 2, 3, 4, 5, 6, 7};
        start(scenario, new AbstractHandler()
        {
            @Override
            public void handle(String target, org.eclipse.jetty.server.Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                response.getOutputStream().write(data);
                baseRequest.setHandled(true);
            }
        });

        client.setConnectBlocking(true);
        ContentResponse response = client.GET(scenario.getScheme() + "://localhost:" + connector.getLocalPort());

        assertNotNull(response);
        assertEquals(200, response.getStatus());
        byte[] content = response.getContent();
        assertArrayEquals(data, content);
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testGETWithParametersResponseWithContent(Scenario scenario) throws Exception
    {
        String paramName1 = "a";
        String paramName2 = "b";
        start(scenario, new AbstractHandler()
        {
            @Override
            public void handle(String target, org.eclipse.jetty.server.Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                response.setCharacterEncoding("UTF-8");
                ServletOutputStream output = response.getOutputStream();
                String paramValue1 = request.getParameter(paramName1);
                output.write(paramValue1.getBytes(StandardCharsets.UTF_8));
                String paramValue2 = request.getParameter(paramName2);
                assertEquals("", paramValue2);
                output.write("empty".getBytes(StandardCharsets.UTF_8));
                baseRequest.setHandled(true);
            }
        });

        String value1 = "\u20AC";
        String paramValue1 = URLEncoder.encode(value1, "UTF-8");
        String query = paramName1 + "=" + paramValue1 + "&" + paramName2;
        ContentResponse response = client.GET(scenario.getScheme() + "://localhost:" + connector.getLocalPort() + "/?" + query);

        assertNotNull(response);
        assertEquals(200, response.getStatus());
        String content = new String(response.getContent(), StandardCharsets.UTF_8);
        assertEquals(value1 + "empty", content);
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testGETWithParametersMultiValuedResponseWithContent(Scenario scenario) throws Exception
    {
        String paramName1 = "a";
        String paramName2 = "b";
        start(scenario, new AbstractHandler()
        {
            @Override
            public void handle(String target, org.eclipse.jetty.server.Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                response.setCharacterEncoding("UTF-8");
                ServletOutputStream output = response.getOutputStream();
                String[] paramValues1 = request.getParameterValues(paramName1);
                for (String paramValue : paramValues1)
                {
                    output.write(paramValue.getBytes(StandardCharsets.UTF_8));
                }
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
        ContentResponse response = client.GET(scenario.getScheme() + "://localhost:" + connector.getLocalPort() + "/?" + query);

        assertNotNull(response);
        assertEquals(200, response.getStatus());
        String content = new String(response.getContent(), StandardCharsets.UTF_8);
        assertEquals(value11 + value12 + value2, content);
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testPOSTWithParameters(Scenario scenario) throws Exception
    {
        String paramName = "a";
        String paramValue = "\u20AC";
        start(scenario, new AbstractHandler()
        {
            @Override
            public void handle(String target, org.eclipse.jetty.server.Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException
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

        ContentResponse response = client.POST(scenario.getScheme() + "://localhost:" + connector.getLocalPort())
            .param(paramName, paramValue)
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertNotNull(response);
        assertEquals(200, response.getStatus());
        assertEquals(paramValue, new String(response.getContent(), StandardCharsets.UTF_8));
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testPUTWithParameters(Scenario scenario) throws Exception
    {
        String paramName = "a";
        String paramValue = "\u20AC";
        String encodedParamValue = URLEncoder.encode(paramValue, "UTF-8");
        start(scenario, new AbstractHandler()
        {
            @Override
            public void handle(String target, org.eclipse.jetty.server.Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException
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

        URI uri = URI.create(scenario.getScheme() + "://localhost:" + connector.getLocalPort() + "/path?" + paramName + "=" + encodedParamValue);
        ContentResponse response = client.newRequest(uri)
            .method(HttpMethod.PUT)
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertNotNull(response);
        assertEquals(200, response.getStatus());
        assertEquals(paramValue, new String(response.getContent(), StandardCharsets.UTF_8));
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testPOSTWithParametersWithContent(Scenario scenario) throws Exception
    {
        byte[] content = {0, 1, 2, 3};
        String paramName = "a";
        String paramValue = "\u20AC";
        start(scenario, new AbstractHandler()
        {
            @Override
            public void handle(String target, org.eclipse.jetty.server.Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException
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

        ContentResponse response = client.POST(scenario.getScheme() + "://localhost:" + connector.getLocalPort() + "/?b=1")
            .param(paramName, paramValue)
            .content(new BytesContentProvider(content))
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertNotNull(response);
        assertEquals(200, response.getStatus());
        assertArrayEquals(content, response.getContent());
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testPOSTWithContentNotifiesRequestContentListener(Scenario scenario) throws Exception
    {
        start(scenario, new AbstractHandler()
        {
            @Override
            public void handle(String target, org.eclipse.jetty.server.Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                baseRequest.setHandled(true);
                consume(request.getInputStream(), true);
            }
        });

        byte[] content = {0, 1, 2, 3};
        ContentResponse response = client.POST(scenario.getScheme() + "://localhost:" + connector.getLocalPort())
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

        assertNotNull(response);
        assertEquals(200, response.getStatus());
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testPOSTWithContentTracksProgress(Scenario scenario) throws Exception
    {
        start(scenario, new AbstractHandler()
        {
            @Override
            public void handle(String target, org.eclipse.jetty.server.Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                baseRequest.setHandled(true);
                consume(request.getInputStream(), true);
            }
        });

        AtomicInteger progress = new AtomicInteger();
        ContentResponse response = client.POST(scenario.getScheme() + "://localhost:" + connector.getLocalPort())
            .onRequestContent((request, buffer) ->
            {
                byte[] bytes = new byte[buffer.remaining()];
                assertEquals(1, bytes.length);
                buffer.get(bytes);
                assertEquals(bytes[0], progress.getAndIncrement());
            })
            .content(new BytesContentProvider(new byte[]{0}, new byte[]{1}, new byte[]{2}, new byte[]{3}, new byte[]{4}))
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertNotNull(response);
        assertEquals(200, response.getStatus());
        assertEquals(5, progress.get());
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testQueuedRequestIsSentWhenPreviousRequestSucceeded(Scenario scenario) throws Exception
    {
        start(scenario, new EmptyServerHandler());

        client.setMaxConnectionsPerDestination(1);

        CountDownLatch latch = new CountDownLatch(1);
        CountDownLatch successLatch = new CountDownLatch(2);
        client.newRequest("localhost", connector.getLocalPort())
            .scheme(scenario.getScheme())
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
                    assertEquals(200, response.getStatus());
                    successLatch.countDown();
                }
            });

        client.newRequest("localhost", connector.getLocalPort())
            .scheme(scenario.getScheme())
            .onRequestQueued(request -> latch.countDown())
            .send(new Response.Listener.Adapter()
            {
                @Override
                public void onSuccess(Response response)
                {
                    assertEquals(200, response.getStatus());
                    successLatch.countDown();
                }
            });

        assertTrue(successLatch.await(5, TimeUnit.SECONDS));
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testQueuedRequestIsSentWhenPreviousRequestClosedConnection(Scenario scenario) throws Exception
    {
        start(scenario, new AbstractHandler()
        {
            @Override
            public void handle(String target, org.eclipse.jetty.server.Request baseRequest, HttpServletRequest request, HttpServletResponse response)
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
            CountDownLatch latch = new CountDownLatch(2);
            client.newRequest("localhost", connector.getLocalPort())
                .scheme(scenario.getScheme())
                .path("/one")
                .onResponseFailure((response, failure) -> latch.countDown())
                .send(null);

            client.newRequest("localhost", connector.getLocalPort())
                .scheme(scenario.getScheme())
                .path("/two")
                .onResponseSuccess(response ->
                {
                    assertEquals(200, response.getStatus());
                    latch.countDown();
                })
                .send(null);

            assertTrue(latch.await(5, TimeUnit.SECONDS));
        }
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testExchangeIsCompleteOnlyWhenBothRequestAndResponseAreComplete(Scenario scenario) throws Exception
    {
        start(scenario, new AbstractHandler()
        {
            @Override
            public void handle(String target, org.eclipse.jetty.server.Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                baseRequest.setHandled(true);
                response.setContentLength(0);
                response.setStatus(200);
                response.flushBuffer();

                byte[] buffer = new byte[1024];
                InputStream in = request.getInputStream();
                while (true)
                {
                    int read = in.read(buffer);
                    if (read < 0)
                        break;
                }
            }
        });

        // Prepare a big file to upload
        Path targetTestsDir = testdir.getEmptyPathDir();
        Files.createDirectories(targetTestsDir);
        Path file = Paths.get(targetTestsDir.toString(), "http_client_conversation.big");
        try (OutputStream output = Files.newOutputStream(file, StandardOpenOption.CREATE))
        {
            byte[] kb = new byte[1024];
            for (int i = 0; i < 10 * 1024; ++i)
            {
                output.write(kb);
            }
        }

        CountDownLatch latch = new CountDownLatch(3);
        AtomicLong exchangeTime = new AtomicLong();
        AtomicLong requestTime = new AtomicLong();
        AtomicLong responseTime = new AtomicLong();
        client.newRequest("localhost", connector.getLocalPort())
            .scheme(scenario.getScheme())
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

        assertTrue(latch.await(10, TimeUnit.SECONDS));

        assertTrue(requestTime.get() <= exchangeTime.get());
        assertTrue(responseTime.get() <= exchangeTime.get());

        // Give some time to the server to consume the request content
        // This is just to avoid exception traces in the test output
        Thread.sleep(1000);

        Files.delete(file);
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testExchangeIsCompleteWhenRequestFailsMidwayWithResponse(Scenario scenario) throws Exception
    {
        start(scenario, new AbstractHandler()
        {
            @Override
            public void handle(String target, org.eclipse.jetty.server.Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                // Echo back
                IO.copy(request.getInputStream(), response.getOutputStream());
            }
        });

        CountDownLatch latch = new CountDownLatch(1);
        client.newRequest("localhost", connector.getLocalPort())
            .scheme(scenario.getScheme())
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
                    return new Iterator<>()
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

        assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testExchangeIsCompleteWhenRequestFailsWithNoResponse(Scenario scenario) throws Exception
    {
        start(scenario, new EmptyServerHandler());

        CountDownLatch latch = new CountDownLatch(1);
        String host = "localhost";
        int port = connector.getLocalPort();
        client.newRequest(host, port)
            .scheme(scenario.getScheme())
            .onRequestBegin(r ->
            {
                HttpDestination destination = (HttpDestination)client.resolveDestination(r);
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

        assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    @DisabledIfSystemProperty(named = "env", matches = "ci") // TODO: SLOW, needs review
    public void testRequestIdleTimeout(Scenario scenario) throws Exception
    {
        long idleTimeout = 1000;
        start(scenario, new AbstractHandler()
        {
            @Override
            public void handle(String target, org.eclipse.jetty.server.Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws ServletException
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

        String host = "localhost";
        int port = connector.getLocalPort();
        assertThrows(TimeoutException.class, () ->
            client.newRequest(host, port)
                .scheme(scenario.getScheme())
                .idleTimeout(idleTimeout, TimeUnit.MILLISECONDS)
                .timeout(3 * idleTimeout, TimeUnit.MILLISECONDS)
                .send());

        // Make another request without specifying the idle timeout, should not fail
        ContentResponse response = client.newRequest(host, port)
            .scheme(scenario.getScheme())
            .timeout(3 * idleTimeout, TimeUnit.MILLISECONDS)
            .send();

        assertNotNull(response);
        assertEquals(200, response.getStatus());
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testSendToIPv6Address(Scenario scenario) throws Exception
    {
        Assumptions.assumeTrue(Net.isIpv6InterfaceAvailable());
        start(scenario, new EmptyServerHandler());

        ContentResponse response = client.newRequest("[::1]", connector.getLocalPort())
            .scheme(scenario.getScheme())
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertNotNull(response);
        assertEquals(200, response.getStatus());
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testHeaderProcessing(Scenario scenario) throws Exception
    {
        String headerName = "X-Header-Test";
        start(scenario, new AbstractHandler()
        {
            @Override
            public void handle(String target, org.eclipse.jetty.server.Request baseRequest, HttpServletRequest request, HttpServletResponse response)
            {
                baseRequest.setHandled(true);
                response.setHeader(headerName, "X");
            }
        });

        ContentResponse response = client.newRequest("localhost", connector.getLocalPort())
            .scheme(scenario.getScheme())
            .onResponseHeader((response1, field) -> !field.getName().equals(headerName))
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertNotNull(response);
        assertEquals(200, response.getStatus());
        assertFalse(response.getHeaders().containsKey(headerName));
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testAllHeadersDiscarded(Scenario scenario) throws Exception
    {
        start(scenario, new EmptyServerHandler());

        int count = 10;
        CountDownLatch latch = new CountDownLatch(count);
        for (int i = 0; i < count; ++i)
        {
            client.newRequest("localhost", connector.getLocalPort())
                .scheme(scenario.getScheme())
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

        assertTrue(latch.await(10, TimeUnit.SECONDS));
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testHEADWithResponseContentLength(Scenario scenario) throws Exception
    {
        int length = 1024;
        start(scenario, new AbstractHandler()
        {
            @Override
            public void handle(String target, org.eclipse.jetty.server.Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                baseRequest.setHandled(true);
                response.getOutputStream().write(new byte[length]);
            }
        });

        // HEAD requests receive a Content-Length header, but do not
        // receive the content so they must handle this case properly
        ContentResponse response = client.newRequest("localhost", connector.getLocalPort())
            .scheme(scenario.getScheme())
            .method(HttpMethod.HEAD)
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertNotNull(response);
        assertEquals(200, response.getStatus());
        assertEquals(0, response.getContent().length);

        // Perform a normal GET request to be sure the content is now read
        response = client.newRequest("localhost", connector.getLocalPort())
            .scheme(scenario.getScheme())
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertNotNull(response);
        assertEquals(200, response.getStatus());
        assertEquals(length, response.getContent().length);
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testConnectThrowsUnknownHostException(Scenario scenario) throws Exception
    {
        String host = "idontexist";
        int port = 80;

        assertThrows(IOException.class, () ->
        {
            Socket socket = new Socket();
            socket.connect(new InetSocketAddress(host, port), 1000);
        }, "Host must not be resolvable");

        start(scenario, new EmptyServerHandler());

        CountDownLatch latch = new CountDownLatch(1);
        client.newRequest(host, port)
            .send(result ->
            {
                assertTrue(result.isFailed());
                Throwable failure = result.getFailure();
                assertTrue(failure instanceof UnknownHostException);
                latch.countDown();
            });
        assertTrue(latch.await(10, TimeUnit.SECONDS));
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testConnectHostWithMultipleAddresses(Scenario scenario) throws Exception
    {
        startServer(scenario, new EmptyServerHandler());
        startClient(scenario, client ->
        {
            client.setSocketAddressResolver(new SocketAddressResolver.Async(client.getExecutor(), client.getScheduler(), 5000)
            {
                @Override
                public void resolve(String host, int port, Promise<List<InetSocketAddress>> promise)
                {
                    super.resolve(host, port, new Promise<>()
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
        });

        // If no exceptions the test passes.
        client.newRequest("localhost", connector.getLocalPort())
            .scheme(scenario.getScheme())
            .header(HttpHeader.CONNECTION, "close")
            .send();
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testCustomUserAgent(Scenario scenario) throws Exception
    {
        String userAgent = "Test/1.0";
        start(scenario, new AbstractHandler()
        {
            @Override
            public void handle(String target, org.eclipse.jetty.server.Request baseRequest, HttpServletRequest request, HttpServletResponse response)
            {
                baseRequest.setHandled(true);
                ArrayList<String> userAgents = Collections.list(request.getHeaders("User-Agent"));
                assertEquals(1, userAgents.size());
                assertEquals(userAgent, userAgents.get(0));
            }
        });

        ContentResponse response = client.newRequest("localhost", connector.getLocalPort())
            .scheme(scenario.getScheme())
            .agent(userAgent)
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertEquals(200, response.getStatus());

        response = client.newRequest("localhost", connector.getLocalPort())
            .scheme(scenario.getScheme())
            .header(HttpHeader.USER_AGENT, null)
            .header(HttpHeader.USER_AGENT, userAgent)
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertEquals(200, response.getStatus());
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testUserAgentCanBeRemoved(Scenario scenario) throws Exception
    {
        start(scenario, new AbstractHandler()
        {
            @Override
            public void handle(String target, org.eclipse.jetty.server.Request baseRequest, HttpServletRequest request, HttpServletResponse response)
            {
                baseRequest.setHandled(true);
                ArrayList<String> userAgents = Collections.list(request.getHeaders("User-Agent"));
                if ("/ua".equals(target))
                    assertEquals(1, userAgents.size());
                else
                    assertEquals(0, userAgents.size());
            }
        });

        // User agent not specified, use default.
        ContentResponse response = client.newRequest("localhost", connector.getLocalPort())
            .scheme(scenario.getScheme())
            .path("/ua")
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertEquals(200, response.getStatus());

        // User agent explicitly removed.
        response = client.newRequest("localhost", connector.getLocalPort())
            .scheme(scenario.getScheme())
            .agent(null)
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertEquals(200, response.getStatus());

        // User agent explicitly removed.
        response = client.newRequest("localhost", connector.getLocalPort())
            .scheme(scenario.getScheme())
            .header(HttpHeader.USER_AGENT, null)
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertEquals(200, response.getStatus());
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testRequestListenerForMultipleEventsIsInvokedOncePerEvent(Scenario scenario) throws Exception
    {
        start(scenario, new EmptyServerHandler());

        AtomicInteger counter = new AtomicInteger();
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
            .scheme(scenario.getScheme())
            .onRequestQueued(listener)
            .onRequestBegin(listener)
            .onRequestHeaders(listener)
            .onRequestCommit(listener)
            .onRequestContent(listener)
            .onRequestSuccess(listener)
            .onRequestFailure(listener)
            .listener(listener)
            .send();

        assertEquals(200, response.getStatus());
        int expectedEventsTriggeredByOnRequestXXXListeners = 5;
        int expectedEventsTriggeredByListener = 5;
        int expected = expectedEventsTriggeredByOnRequestXXXListeners + expectedEventsTriggeredByListener;
        assertEquals(expected, counter.get());
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testResponseListenerForMultipleEventsIsInvokedOncePerEvent(Scenario scenario) throws Exception
    {
        start(scenario, new EmptyServerHandler());

        AtomicInteger counter = new AtomicInteger();
        CountDownLatch latch = new CountDownLatch(1);
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
            public void onContent(Response response, LongConsumer demand, ByteBuffer content, Callback callback)
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
                assertEquals(200, result.getResponse().getStatus());
                counter.incrementAndGet();
                latch.countDown();
            }
        };
        client.newRequest("localhost", connector.getLocalPort())
            .scheme(scenario.getScheme())
            .onResponseBegin(listener)
            .onResponseHeader(listener)
            .onResponseHeaders(listener)
            .onResponseContent(listener)
            .onResponseContentAsync(listener)
            .onResponseSuccess(listener)
            .onResponseFailure(listener)
            .send(listener);

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        int expectedEventsTriggeredByOnResponseXXXListeners = 3;
        int expectedEventsTriggeredByCompletionListener = 4;
        int expected = expectedEventsTriggeredByOnResponseXXXListeners + expectedEventsTriggeredByCompletionListener;
        assertEquals(expected, counter.get());
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void setOnCompleteCallbackWithBlockingSend(Scenario scenario) throws Exception
    {
        byte[] content = new byte[512];
        new Random().nextBytes(content);
        start(scenario, new AbstractHandler()
        {
            @Override
            public void handle(String target, org.eclipse.jetty.server.Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                baseRequest.setHandled(true);
                response.getOutputStream().write(content);
            }
        });

        Exchanger<Response> ex = new Exchanger<>();
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
            .scheme(scenario.getScheme())
            .send(listener);

        Response response = ex.exchange(null);

        assertEquals(200, response.getStatus());
        assertArrayEquals(content, listener.getContent());
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testCustomHostHeader(Scenario scenario) throws Exception
    {
        String host = "localhost";
        start(scenario, new AbstractHandler()
        {
            @Override
            public void handle(String target, org.eclipse.jetty.server.Request baseRequest, HttpServletRequest request, HttpServletResponse response)
            {
                baseRequest.setHandled(true);
                assertEquals(host, request.getServerName());
            }
        });

        ContentResponse response = client.newRequest("http://127.0.0.1:" + connector.getLocalPort() + "/path")
            .scheme(scenario.getScheme())
            .header(HttpHeader.HOST, host)
            .send();

        assertEquals(200, response.getStatus());
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testHTTP10WithKeepAliveAndContentLength(Scenario scenario) throws Exception
    {
        start(scenario, new AbstractHandler()
        {
            @Override
            public void handle(String target, org.eclipse.jetty.server.Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                // Send the headers at this point, then write the content
                byte[] content = "TEST".getBytes("UTF-8");
                response.setContentLength(content.length);
                response.flushBuffer();
                response.getOutputStream().write(content);
            }
        });

        ContentResponse response = client.newRequest("localhost", connector.getLocalPort())
            .scheme(scenario.getScheme())
            .version(HttpVersion.HTTP_1_0)
            .header(HttpHeader.CONNECTION, HttpHeaderValue.KEEP_ALIVE.asString())
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertEquals(200, response.getStatus());
        assertTrue(response.getHeaders().contains(HttpHeader.CONNECTION, HttpHeaderValue.KEEP_ALIVE.asString()));
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testHTTP10WithKeepAliveAndNoContentLength(Scenario scenario) throws Exception
    {
        start(scenario, new AbstractHandler()
        {
            @Override
            public void handle(String target, org.eclipse.jetty.server.Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                // Send the headers at this point, then write the content
                response.flushBuffer();
                response.getOutputStream().print("TEST");
            }
        });

        long timeout = 5000;
        Request request = client.newRequest("localhost", connector.getLocalPort())
            .scheme(scenario.getScheme())
            .version(HttpVersion.HTTP_1_0)
            .header(HttpHeader.CONNECTION, HttpHeaderValue.KEEP_ALIVE.asString())
            .timeout(timeout, TimeUnit.MILLISECONDS);
        FuturePromise<Connection> promise = new FuturePromise<>();
        Destination destination = client.resolveDestination(request);
        destination.newConnection(promise);
        try (Connection connection = promise.get(5, TimeUnit.SECONDS))
        {
            FutureResponseListener listener = new FutureResponseListener(request);
            connection.send(request, listener);
            ContentResponse response = listener.get(2 * timeout, TimeUnit.MILLISECONDS);

            assertEquals(200, response.getStatus());
            // The parser notifies end-of-content and therefore the CompleteListener
            // before closing the connection, so we need to wait before checking
            // that the connection is closed to avoid races.
            Thread.sleep(1000);
            assertTrue(connection.isClosed());
        }
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testHTTP10WithKeepAliveAndNoContent(Scenario scenario) throws Exception
    {
        start(scenario, new EmptyServerHandler());

        ContentResponse response = client.newRequest("localhost", connector.getLocalPort())
            .scheme(scenario.getScheme())
            .version(HttpVersion.HTTP_1_0)
            .header(HttpHeader.CONNECTION, HttpHeaderValue.KEEP_ALIVE.asString())
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertEquals(200, response.getStatus());
        assertTrue(response.getHeaders().contains(HttpHeader.CONNECTION, HttpHeaderValue.KEEP_ALIVE.asString()));
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testLongPollIsAbortedWhenClientIsStopped(Scenario scenario) throws Exception
    {
        CountDownLatch latch = new CountDownLatch(1);
        start(scenario, new AbstractHandler()
        {
            @Override
            public void handle(String target, org.eclipse.jetty.server.Request baseRequest, HttpServletRequest request, HttpServletResponse response)
            {
                baseRequest.setHandled(true);
                request.startAsync();
                latch.countDown();
            }
        });

        CountDownLatch completeLatch = new CountDownLatch(1);
        client.newRequest("localhost", connector.getLocalPort())
            .scheme(scenario.getScheme())
            .send(result ->
            {
                if (result.isFailed())
                    completeLatch.countDown();
            });

        assertTrue(latch.await(5, TimeUnit.SECONDS));

        // Stop the client, the complete listener must be invoked.
        client.stop();

        assertTrue(completeLatch.await(5, TimeUnit.SECONDS));
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testSmallContentDelimitedByEOFWithSlowRequestHTTP10(Scenario scenario)
    {
        Assumptions.assumeTrue(HttpScheme.HTTP.is(scenario.getScheme()));

        ExecutionException e = assertThrows(ExecutionException.class, () ->
            testContentDelimitedByEOFWithSlowRequest(scenario, HttpVersion.HTTP_1_0, 1024));

        assertThat(e.getCause(), instanceOf(BadMessageException.class));
        assertThat(e.getCause().getMessage(), containsString("Unknown content"));
    }

    @ParameterizedTest
    @ArgumentsSource(NonSslScenarioProvider.class)
    public void testBigContentDelimitedByEOFWithSlowRequestHTTP10(Scenario scenario)
    {
        ExecutionException e = assertThrows(ExecutionException.class, () ->
            testContentDelimitedByEOFWithSlowRequest(scenario, HttpVersion.HTTP_1_0, 128 * 1024));

        assertThat(e.getCause(), instanceOf(BadMessageException.class));
        assertThat(e.getCause().getMessage(), containsString("Unknown content"));
    }

    @ParameterizedTest
    @ArgumentsSource(NonSslScenarioProvider.class)
    public void testSmallContentDelimitedByEOFWithSlowRequestHTTP11(Scenario scenario) throws Exception
    {
        testContentDelimitedByEOFWithSlowRequest(scenario, HttpVersion.HTTP_1_1, 1024);
    }

    @ParameterizedTest
    @ArgumentsSource(NonSslScenarioProvider.class)
    public void testBigContentDelimitedByEOFWithSlowRequestHTTP11(Scenario scenario) throws Exception
    {
        testContentDelimitedByEOFWithSlowRequest(scenario, HttpVersion.HTTP_1_1, 128 * 1024);
    }

    private void testContentDelimitedByEOFWithSlowRequest(Scenario scenario, HttpVersion version, int length) throws Exception
    {
        // This test is crafted in a way that the response completes before the request is fully written.
        // With SSL, the response coming down will close the SSLEngine so it would not be possible to
        // write the last chunk of the request content, and the request will be failed, failing also the
        // test, which is not what we want.
        // This is a limit of Java's SSL implementation that does not allow half closes.
        Assumptions.assumeTrue(HttpScheme.HTTP.is(scenario.getScheme()));

        byte[] data = new byte[length];
        new Random().nextBytes(data);
        start(scenario, new AbstractHandler()
        {
            @Override
            public void handle(String target, org.eclipse.jetty.server.Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException
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
            .scheme(scenario.getScheme())
            .version(version)
            .content(content);
        FutureResponseListener listener = new FutureResponseListener(request);
        request.send(listener);
        // Wait some time to simulate a slow request.
        Thread.sleep(1000);
        content.close();

        ContentResponse response = listener.get(5, TimeUnit.SECONDS);

        assertEquals(200, response.getStatus());
        assertArrayEquals(data, response.getContent());
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testRequestRetries(Scenario scenario) throws Exception
    {
        int maxRetries = 3;
        AtomicInteger requests = new AtomicInteger();
        start(scenario, new AbstractHandler()
        {
            @Override
            public void handle(String target, org.eclipse.jetty.server.Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                int count = requests.incrementAndGet();
                if (count == maxRetries)
                    baseRequest.setHandled(true);
                consume(request.getInputStream(), true);
            }
        });

        CountDownLatch latch = new CountDownLatch(1);
        new RetryListener(client, scenario.getScheme(), "localhost", connector.getLocalPort(), maxRetries)
        {
            @Override
            protected void completed(Result result)
            {
                latch.countDown();
            }
        }.perform();

        assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testCompleteNotInvokedUntilContentConsumed(Scenario scenario) throws Exception
    {
        start(scenario, new AbstractHandler()
        {
            @Override
            public void handle(String target, org.eclipse.jetty.server.Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                baseRequest.setHandled(true);
                ServletOutputStream output = response.getOutputStream();
                output.write(new byte[1024]);
            }
        });

        AtomicReference<Callback> callbackRef = new AtomicReference<>();
        CountDownLatch contentLatch = new CountDownLatch(1);
        CountDownLatch completeLatch = new CountDownLatch(1);
        client.newRequest("localhost", connector.getLocalPort())
            .scheme(scenario.getScheme())
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

        assertTrue(contentLatch.await(5, TimeUnit.SECONDS));

        // Make sure the complete event is not emitted.
        assertFalse(completeLatch.await(1, TimeUnit.SECONDS));

        // Consume the content.
        callbackRef.get().succeeded();

        // Now the complete event is emitted.
        assertTrue(completeLatch.await(5, TimeUnit.SECONDS));
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testRequestSentOnlyAfterConnectionOpen(Scenario scenario) throws Exception
    {
        startServer(scenario, new AbstractHandler()
        {
            @Override
            public void handle(String target, org.eclipse.jetty.server.Request baseRequest, HttpServletRequest request, HttpServletResponse response)
            {
                baseRequest.setHandled(true);
            }
        });

        AtomicBoolean open = new AtomicBoolean();
        ClientConnector clientConnector = new ClientConnector();
        clientConnector.setSslContextFactory(scenario.newClientSslContextFactory());
        client = new HttpClient(new HttpClientTransportOverHTTP(clientConnector)
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
        });
        client.start();

        CountDownLatch latch = new CountDownLatch(2);
        client.newRequest("localhost", connector.getLocalPort())
            .scheme(scenario.getScheme())
            .onRequestBegin(request ->
            {
                assertTrue(open.get());
                latch.countDown();
            })
            .send(result ->
            {
                if (result.isSucceeded())
                    latch.countDown();
            });

        assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testCONNECTWithHTTP10(Scenario scenario) throws Exception
    {
        try (ServerSocket server = new ServerSocket(0))
        {
            startClient(scenario);

            String host = "localhost";
            int port = server.getLocalPort();

            Request request = client.newRequest(host, port)
                .method(HttpMethod.CONNECT)
                .version(HttpVersion.HTTP_1_0);
            FuturePromise<Connection> promise = new FuturePromise<>();
            client.resolveDestination(request).newConnection(promise);
            Connection connection = promise.get(5, TimeUnit.SECONDS);
            FutureResponseListener listener = new FutureResponseListener(request);
            connection.send(request, listener);

            try (Socket socket = server.accept())
            {
                InputStream input = socket.getInputStream();
                consume(input, false);

                // HTTP/1.0 response, the client must not close the connection.
                String httpResponse =
                    "HTTP/1.0 200 OK\r\n" +
                        "\r\n";
                OutputStream output = socket.getOutputStream();
                output.write(httpResponse.getBytes(StandardCharsets.UTF_8));
                output.flush();

                ContentResponse response = listener.get(5, TimeUnit.SECONDS);
                assertEquals(200, response.getStatus());

                // Because the tunnel was successful, this connection will be
                // upgraded to an SslConnection, so it will not be fill interested.
                // This test doesn't upgrade, so it needs to restore the fill interest.
                ((AbstractConnection)connection).fillInterested();

                // Test that I can send another request on the same connection.
                request = client.newRequest(host, port);
                listener = new FutureResponseListener(request);
                connection.send(request, listener);

                consume(input, false);

                httpResponse =
                    "HTTP/1.1 200 OK\r\n" +
                        "Content-Length: 0\r\n" +
                        "\r\n";
                output.write(httpResponse.getBytes(StandardCharsets.UTF_8));
                output.flush();

                listener.get(5, TimeUnit.SECONDS);
            }
        }
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testIPv6Host(Scenario scenario) throws Exception
    {
        Assumptions.assumeTrue(Net.isIpv6InterfaceAvailable());
        start(scenario, new AbstractHandler()
        {
            @Override
            public void handle(String target, org.eclipse.jetty.server.Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                baseRequest.setHandled(true);
                response.setContentType("text/plain");
                response.getOutputStream().print(request.getHeader("Host"));
            }
        });

        URI uri = URI.create(scenario.getScheme() + "://[::1]:" + connector.getLocalPort() + "/path");
        ContentResponse response = client.newRequest(uri)
            .method(HttpMethod.PUT)
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertNotNull(response);
        assertEquals(200, response.getStatus());
        assertThat(new String(response.getContent(), StandardCharsets.ISO_8859_1), Matchers.startsWith("[::1]:"));
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testCopyRequest(Scenario scenario) throws Exception
    {
        startClient(scenario);

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

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testHostWithHTTP10(Scenario scenario) throws Exception
    {
        start(scenario, new AbstractHandler()
        {
            @Override
            public void handle(String target, org.eclipse.jetty.server.Request baseRequest, HttpServletRequest request, HttpServletResponse response)
            {
                baseRequest.setHandled(true);
                assertThat(request.getHeader("Host"), Matchers.notNullValue());
            }
        });

        ContentResponse response = client.newRequest("localhost", connector.getLocalPort())
            .scheme(scenario.getScheme())
            .version(HttpVersion.HTTP_1_0)
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertEquals(200, response.getStatus());
    }

    @ParameterizedTest
    @ArgumentsSource(NonSslScenarioProvider.class)
    public void test204WithContent(Scenario scenario) throws Exception
    {
        // This test only works with clear-text HTTP.
        Assumptions.assumeTrue(HttpScheme.HTTP.is(scenario.getScheme()));

        try (ServerSocket server = new ServerSocket(0))
        {
            int idleTimeout = 2000;
            startClient(scenario, httpClient ->
            {
                httpClient.setMaxConnectionsPerDestination(1);
                httpClient.setIdleTimeout(idleTimeout);
            });

            Request request = client.newRequest("localhost", server.getLocalPort())
                .scheme(scenario.getScheme())
                .timeout(5, TimeUnit.SECONDS);
            FutureResponseListener listener = new FutureResponseListener(request);
            request.send(listener);

            try (Socket socket = server.accept())
            {
                socket.setSoTimeout(idleTimeout / 2);

                InputStream input = socket.getInputStream();
                consume(input, false);

                // Send a bad response.
                String httpResponse =
                    "HTTP/1.1 204 No Content\r\n" +
                        "\r\n" +
                        "No Content";
                OutputStream output = socket.getOutputStream();
                output.write(httpResponse.getBytes(StandardCharsets.UTF_8));
                output.flush();

                ContentResponse response = listener.get(5, TimeUnit.SECONDS);
                assertEquals(204, response.getStatus());

                byte[] responseContent = response.getContent();
                assertNotNull(responseContent);
                assertEquals(0, responseContent.length);

                // Send another request to verify we have handled the wrong response correctly.
                request = client.newRequest("localhost", server.getLocalPort())
                    .scheme(scenario.getScheme())
                    .timeout(5, TimeUnit.SECONDS);
                listener = new FutureResponseListener(request);
                request.send(listener);

                consume(input, false);

                httpResponse =
                    "HTTP/1.1 200 OK\r\n" +
                        "Content-Length: 0\r\n" +
                        "\r\n";
                output.write(httpResponse.getBytes(StandardCharsets.UTF_8));
                output.flush();

                response = listener.get(5, TimeUnit.SECONDS);
                assertEquals(200, response.getStatus());
            }
        }
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testContentListenerAsCompleteListener(Scenario scenario) throws Exception
    {
        byte[] bytes = new byte[1024];
        new Random().nextBytes(bytes);
        start(scenario, new AbstractHandler()
        {
            @Override
            public void handle(String target, org.eclipse.jetty.server.Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                baseRequest.setHandled(true);
                ServletOutputStream output = response.getOutputStream();
                output.write(bytes);
            }
        });

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        CountDownLatch latch = new CountDownLatch(1);
        class L implements Response.ContentListener, Response.CompleteListener
        {
            @Override
            public void onContent(Response response, ByteBuffer content)
            {
                try
                {
                    BufferUtil.writeTo(content, baos);
                }
                catch (IOException x)
                {
                    baos.reset();
                    x.printStackTrace();
                }
            }

            @Override
            public void onComplete(Result result)
            {
                if (result.isSucceeded())
                    latch.countDown();
            }
        }

        client.newRequest("localhost", connector.getLocalPort())
            .scheme(scenario.getScheme())
            .send(new L());

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertArrayEquals(bytes, baos.toByteArray());
    }

    private void assertCopyRequest(Request original)
    {
        Request copy = client.copyRequest((HttpRequest)original, original.getURI());
        assertEquals(original.getURI(), copy.getURI());
        assertEquals(original.getMethod(), copy.getMethod());
        assertEquals(original.getVersion(), copy.getVersion());
        assertEquals(original.getContent(), copy.getContent());
        assertEquals(original.getIdleTimeout(), copy.getIdleTimeout());
        assertEquals(original.getTimeout(), copy.getTimeout());
        assertEquals(original.isFollowRedirects(), copy.isFollowRedirects());
        assertEquals(original.getHeaders(), copy.getHeaders());
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

    public abstract static class RetryListener implements Response.CompleteListener
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
