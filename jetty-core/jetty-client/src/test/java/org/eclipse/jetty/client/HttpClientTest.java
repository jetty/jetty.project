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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Exchanger;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongConsumer;

import org.eclipse.jetty.client.api.Connection;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Destination;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.client.api.Result;
import org.eclipse.jetty.client.http.HttpClientTransportOverHTTP;
import org.eclipse.jetty.client.http.HttpConnectionOverHTTP;
import org.eclipse.jetty.client.util.AsyncRequestContent;
import org.eclipse.jetty.client.util.BufferingResponseListener;
import org.eclipse.jetty.client.util.BytesRequestContent;
import org.eclipse.jetty.client.util.FutureResponseListener;
import org.eclipse.jetty.client.util.StringRequestContent;
import org.eclipse.jetty.http.BadMessageException;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpHeaderValue;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpScheme;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.io.ClientConnector;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.logging.StacklessLogging;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.internal.HttpChannelState;
import org.eclipse.jetty.toolchain.test.Net;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Fields;
import org.eclipse.jetty.util.FuturePromise;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.NanoTime;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.SocketAddressResolver;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.awaitility.Awaitility.await;
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

        long start = NanoTime.now();
        HttpConnectionOverHTTP connection = null;
        while (connection == null && NanoTime.secondsSince(start) < 5)
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
        start(scenario, new Handler.Processor()
        {
            @Override
            public void process(org.eclipse.jetty.server.Request request, org.eclipse.jetty.server.Response response, Callback callback)
            {
                response.write(true, ByteBuffer.wrap(data), callback);
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
        start(scenario, new EmptyServerHandler()
        {
            @Override
            protected void service(org.eclipse.jetty.server.Request request, org.eclipse.jetty.server.Response response) throws Throwable
            {
                response.getHeaders().put(HttpHeader.CONTENT_TYPE, "text/plain;charset=UTF-8");
                Fields fields = org.eclipse.jetty.server.Request.extractQueryParameters(request);
                String paramValue1 = fields.getValue(paramName1);
                Content.Sink.write(response, false, UTF_8.encode(paramValue1));
                String paramValue2 = fields.getValue(paramName2);
                assertEquals("", paramValue2);
                Content.Sink.write(response, true, UTF_8.encode("empty"));
            }
        });

        String value1 = "\u20AC";
        String paramValue1 = URLEncoder.encode(value1, UTF_8);
        String query = paramName1 + "=" + paramValue1 + "&" + paramName2;
        ContentResponse response = client.GET(scenario.getScheme() + "://localhost:" + connector.getLocalPort() + "/?" + query);

        assertNotNull(response);
        assertEquals(200, response.getStatus());
        String content = new String(response.getContent(), UTF_8);
        assertEquals(value1 + "empty", content);
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testGETWithParametersMultiValuedResponseWithContent(Scenario scenario) throws Exception
    {
        String paramName1 = "a";
        String paramName2 = "b";
        start(scenario, new EmptyServerHandler()
        {
            @Override
            protected void service(org.eclipse.jetty.server.Request request, org.eclipse.jetty.server.Response response) throws Throwable
            {
                response.getHeaders().put(HttpHeader.CONTENT_TYPE, "text/plain;charset=UTF-8");
                Fields fields = org.eclipse.jetty.server.Request.extractQueryParameters(request);

                List<String> paramValues1 = fields.getValues(paramName1);
                for (String paramValue : paramValues1)
                {
                    Content.Sink.write(response, false, UTF_8.encode(paramValue));
                }
                String paramValue2 = fields.getValue(paramName2);
                Content.Sink.write(response, true, UTF_8.encode(paramValue2));
            }
        });

        String value11 = "\u20AC";
        String value12 = "\u20AA";
        String value2 = "&";
        String paramValue11 = URLEncoder.encode(value11, UTF_8);
        String paramValue12 = URLEncoder.encode(value12, UTF_8);
        String paramValue2 = URLEncoder.encode(value2, UTF_8);
        String query = paramName1 + "=" + paramValue11 + "&" + paramName1 + "=" + paramValue12 + "&" + paramName2 + "=" + paramValue2;
        ContentResponse response = client.GET(scenario.getScheme() + "://localhost:" + connector.getLocalPort() + "/?" + query);

        assertNotNull(response);
        assertEquals(200, response.getStatus());
        String content = new String(response.getContent(), UTF_8);
        assertEquals(value11 + value12 + value2, content);
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testPOSTWithParameters(Scenario scenario) throws Exception
    {
        String paramName = "a";
        String paramValue = "\u20AC";
        start(scenario, new Handler.Processor()
        {
            @Override
            public void process(org.eclipse.jetty.server.Request request, org.eclipse.jetty.server.Response response, Callback callback)
            {
                Fields fields = org.eclipse.jetty.server.Request.extractQueryParameters(request);
                String value = fields.getValue(paramName);
                if (paramValue.equals(value))
                {
                    response.getHeaders().put(HttpHeader.CONTENT_TYPE, "text/plain;charset=UTF-8");
                    Content.Sink.write(response, true, value, callback);
                }
            }
        });

        ContentResponse response = client.POST(scenario.getScheme() + "://localhost:" + connector.getLocalPort())
            .param(paramName, paramValue)
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertNotNull(response);
        assertEquals(200, response.getStatus());
        assertEquals(paramValue, new String(response.getContent(), UTF_8));
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testPUTWithParameters(Scenario scenario) throws Exception
    {
        String paramName = "a";
        String paramValue = "\u20AC";
        String encodedParamValue = URLEncoder.encode(paramValue, UTF_8);
        start(scenario, new Handler.Processor()
        {
            @Override
            public void process(org.eclipse.jetty.server.Request request, org.eclipse.jetty.server.Response response, Callback callback)
            {
                Fields fields = org.eclipse.jetty.server.Request.extractQueryParameters(request);
                String value = fields.getValue(paramName);
                if (paramValue.equals(value))
                {
                    response.getHeaders().put(HttpHeader.CONTENT_TYPE, "text/plain;charset=UTF-8");
                    Content.Sink.write(response, true, value, callback);
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
        assertEquals(paramValue, new String(response.getContent(), UTF_8));
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testPOSTWithParametersWithContent(Scenario scenario) throws Exception
    {
        byte[] content = {0, 1, 2, 3};
        String paramName = "a";
        String paramValue = "\u20AC";
        start(scenario, new Handler.Processor()
        {
            @Override
            public void process(org.eclipse.jetty.server.Request request, org.eclipse.jetty.server.Response response, Callback callback) throws Exception
            {
                Content.Source.consumeAll(request);
                Fields fields = org.eclipse.jetty.server.Request.extractQueryParameters(request);
                String value = fields.getValue(paramName);
                if (paramValue.equals(value))
                {
                    response.getHeaders().put(HttpHeader.CONTENT_TYPE, "text/plain;charset=UTF-8");
                    response.write(true, ByteBuffer.wrap(content), callback);
                }
            }
        });

        ContentResponse response = client.POST(scenario.getScheme() + "://localhost:" + connector.getLocalPort() + "/?b=1")
            .param(paramName, paramValue)
            .body(new BytesRequestContent(content))
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
        start(scenario, new EmptyServerHandler()
        {
            @Override
            protected void service(org.eclipse.jetty.server.Request request, org.eclipse.jetty.server.Response response) throws Throwable
            {
                Content.Source.consumeAll(request);
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
            .body(new BytesRequestContent(content))
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertNotNull(response);
        assertEquals(200, response.getStatus());
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testPOSTWithContentTracksProgress(Scenario scenario) throws Exception
    {
        start(scenario, new EmptyServerHandler()
        {
            @Override
            protected void service(org.eclipse.jetty.server.Request request, org.eclipse.jetty.server.Response response) throws Throwable
            {
                Content.Source.consumeAll(request);
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
            .body(new BytesRequestContent(new byte[]{0}, new byte[]{1}, new byte[]{2}, new byte[]{3}, new byte[]{4}))
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
        start(scenario, new EmptyServerHandler()
        {
            @Override
            protected void service(org.eclipse.jetty.server.Request request, org.eclipse.jetty.server.Response response)
            {
                if (request.getPathInContext().endsWith("/one"))
                    request.getConnectionMetaData().getConnection().getEndPoint().close();
            }
        });

        client.setMaxConnectionsPerDestination(1);

        try (StacklessLogging ignored = new StacklessLogging(HttpChannelState.class))
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
    public void testRetryWithDestinationIdleTimeoutEnabled(Scenario scenario) throws Exception
    {
        start(scenario, new EmptyServerHandler());
        client.stop();
        client.setDestinationIdleTimeout(1000);
        client.setIdleTimeout(1000);
        client.setMaxConnectionsPerDestination(1);
        client.start();

        try (StacklessLogging ignored = new StacklessLogging(org.eclipse.jetty.server.HttpChannel.class))
        {
            client.newRequest("localhost", connector.getLocalPort())
                .scheme(scenario.getScheme())
                .path("/one")
                .send();

            int idleTimeout = 100;
            Thread.sleep(idleTimeout * 2);

            // After serving a request over a connection that hasn't timed out, serving a second
            // request with a shorter idle timeout will make the connection timeout immediately
            // after being taken out of the pool. This triggers the retry mechanism.
            client.newRequest("localhost", connector.getLocalPort())
                .scheme(scenario.getScheme())
                .path("/two")
                .idleTimeout(idleTimeout, TimeUnit.MILLISECONDS)
                .send();
        }

        // Wait for the sweeper to remove the idle HttpDestination.
        await().atMost(5, TimeUnit.SECONDS).until(() -> client.getDestinations().isEmpty());
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testExchangeIsCompleteOnlyWhenBothRequestAndResponseAreComplete(Scenario scenario) throws Exception
    {
        start(scenario, new EmptyServerHandler()
        {
            @Override
            protected void service(org.eclipse.jetty.server.Request request, org.eclipse.jetty.server.Response response) throws Throwable
            {
                response.getHeaders().putLongField(HttpHeader.CONTENT_LENGTH, 0);
                response.setStatus(HttpStatus.OK_200);
                Content.Sink.write(response, true, null);
                Content.Source.consumeAll(request);
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
                requestTime.set(NanoTime.now());
                latch.countDown();
            })
            .send(new Response.Listener.Adapter()
            {
                @Override
                public void onSuccess(Response response)
                {
                    responseTime.set(NanoTime.now());
                    latch.countDown();
                }

                @Override
                public void onComplete(Result result)
                {
                    exchangeTime.set(NanoTime.now());
                    latch.countDown();
                }
            });

        assertTrue(latch.await(10, TimeUnit.SECONDS));

        assertTrue(NanoTime.isBeforeOrSame(requestTime.get(), exchangeTime.get()));
        assertTrue(NanoTime.isBeforeOrSame(responseTime.get(), exchangeTime.get()));

        // Give some time to the server to consume the request content
        // This is just to avoid exception traces in the test output
        Thread.sleep(1000);

        Files.delete(file);
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testExchangeIsCompleteWhenRequestFailsMidwayWithResponse(Scenario scenario) throws Exception
    {
        start(scenario, new EmptyServerHandler()
        {
            @Override
            protected void service(org.eclipse.jetty.server.Request request, org.eclipse.jetty.server.Response response) throws Throwable
            {
                // Echo back
                InputStream inputStream = org.eclipse.jetty.server.Request.asInputStream(request);
                OutputStream outputStream = Content.Sink.asOutputStream(response);
                IO.copy(inputStream, outputStream);
            }
        });

        AsyncRequestContent body = new AsyncRequestContent();
        body.write(false, BufferUtil.allocate(512), Callback.NOOP);
        body.write(false, BufferUtil.allocate(512), Callback.NOOP);
        body.write(Content.Chunk.from(new IOException("explicitly_thrown_by_test")), Callback.NOOP);
        CountDownLatch latch = new CountDownLatch(1);
        client.newRequest("localhost", connector.getLocalPort())
            .scheme(scenario.getScheme())
            .body(body)
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
        start(scenario, new EmptyServerHandler()
        {
            @Override
            protected void service(org.eclipse.jetty.server.Request request, org.eclipse.jetty.server.Response response)
            {
                response.getHeaders().put(headerName, "X");
            }
        });

        ContentResponse response = client.newRequest("localhost", connector.getLocalPort())
            .scheme(scenario.getScheme())
            .onResponseHeader((response1, field) -> !field.getName().equals(headerName))
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertNotNull(response);
        assertEquals(200, response.getStatus());
        assertFalse(response.getHeaders().contains(headerName));
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
        start(scenario, new Handler.Processor()
        {
            @Override
            public void process(org.eclipse.jetty.server.Request request, org.eclipse.jetty.server.Response response, Callback callback)
            {
                response.write(true, ByteBuffer.wrap(new byte[length]), callback);
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
            .headers(headers -> headers.put(HttpHeader.CONNECTION, HttpHeaderValue.CLOSE))
            .send();
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testCustomUserAgent(Scenario scenario) throws Exception
    {
        String userAgent = "Test/1.0";
        start(scenario, new EmptyServerHandler()
        {
            @Override
            protected void service(org.eclipse.jetty.server.Request request, org.eclipse.jetty.server.Response response)
            {
                List<String> userAgents = request.getHeaders().getValuesList(HttpHeader.USER_AGENT);
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
            .headers(headers -> headers.put(HttpHeader.USER_AGENT, userAgent))
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertEquals(200, response.getStatus());
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testUserAgentCanBeRemoved(Scenario scenario) throws Exception
    {
        start(scenario, new EmptyServerHandler()
        {
            @Override
            protected void service(org.eclipse.jetty.server.Request request, org.eclipse.jetty.server.Response response)
            {
                List<String> userAgents = request.getHeaders().getValuesList(HttpHeader.USER_AGENT);
                if ("/ua".equals(request.getPathInContext()))
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
            .headers(headers -> headers.remove(HttpHeader.USER_AGENT))
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
        start(scenario, new Handler.Processor()
        {
            @Override
            public void process(org.eclipse.jetty.server.Request request, org.eclipse.jetty.server.Response response, Callback callback)
            {
                response.write(true, ByteBuffer.wrap(content), callback);
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
        start(scenario, new EmptyServerHandler()
        {
            @Override
            protected void service(org.eclipse.jetty.server.Request request, org.eclipse.jetty.server.Response response)
            {
                assertEquals(host, org.eclipse.jetty.server.Request.getServerName(request));
            }
        });

        ContentResponse response = client.newRequest("http://127.0.0.1:" + connector.getLocalPort() + "/path")
            .scheme(scenario.getScheme())
            .headers(headers -> headers.put(HttpHeader.HOST, host))
            .send();

        assertEquals(200, response.getStatus());
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testHTTP10WithKeepAliveAndContentLength(Scenario scenario) throws Exception
    {
        start(scenario, new Handler.Processor()
        {
            @Override
            public void process(org.eclipse.jetty.server.Request request, org.eclipse.jetty.server.Response response, Callback callback) throws Exception
            {
                // Send the headers at this point, then write the content.
                byte[] content = "TEST".getBytes(UTF_8);
                response.getHeaders().putLongField(HttpHeader.CONTENT_LENGTH, content.length);
                Content.Sink.write(response, false, null);
                response.write(true, ByteBuffer.wrap(content), callback);
            }
        });

        ContentResponse response = client.newRequest("localhost", connector.getLocalPort())
            .scheme(scenario.getScheme())
            .version(HttpVersion.HTTP_1_0)
            .headers(headers -> headers.put(HttpHeader.CONNECTION, HttpHeaderValue.KEEP_ALIVE.asString()))
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertEquals(200, response.getStatus());
        assertTrue(response.getHeaders().contains(HttpHeader.CONNECTION, HttpHeaderValue.KEEP_ALIVE.asString()));
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testHTTP10WithKeepAliveAndNoContentLength(Scenario scenario) throws Exception
    {
        start(scenario, new Handler.Processor()
        {
            @Override
            public void process(org.eclipse.jetty.server.Request request, org.eclipse.jetty.server.Response response, Callback callback) throws Exception
            {
                // Send the headers at this point, then write the content
                Content.Sink.write(response, false, null);
                Content.Sink.write(response, true, "TEST", callback);
            }
        });

        long timeout = 5000;
        Request request = client.newRequest("localhost", connector.getLocalPort())
            .scheme(scenario.getScheme())
            .version(HttpVersion.HTTP_1_0)
            .headers(headers -> headers.put(HttpHeader.CONNECTION, HttpHeaderValue.KEEP_ALIVE.asString()))
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
            .headers(headers -> headers.put(HttpHeader.CONNECTION, HttpHeaderValue.KEEP_ALIVE.asString()))
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
        start(scenario, new Handler.Processor()
        {
            @Override
            public void process(org.eclipse.jetty.server.Request request, org.eclipse.jetty.server.Response response, Callback callback)
            {
                // Do not complete the callback.
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
        start(scenario, new Handler.Processor()
        {
            @Override
            public void process(org.eclipse.jetty.server.Request request, org.eclipse.jetty.server.Response response, Callback callback)
            {
                // Send Connection: close to avoid that the server chunks the content with HTTP 1.1.
                if (version.compareTo(HttpVersion.HTTP_1_0) > 0)
                    response.getHeaders().put("Connection", "close");
                response.write(true, ByteBuffer.wrap(data), callback);
            }
        });

        AsyncRequestContent content = new AsyncRequestContent(ByteBuffer.wrap(new byte[]{0}));
        Request request = client.newRequest("localhost", connector.getLocalPort())
            .scheme(scenario.getScheme())
            .version(version)
            .body(content);
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
        CountDownLatch latch = new CountDownLatch(2);
        start(scenario, new Handler.Processor()
        {
            @Override
            public void process(org.eclipse.jetty.server.Request request, org.eclipse.jetty.server.Response response, Callback callback) throws Exception
            {
                Content.Source.consumeAll(request);
                int count = requests.incrementAndGet();
                if (count == maxRetries)
                    latch.countDown();
                else
                    response.setStatus(HttpStatus.NOT_FOUND_404);
                callback.succeeded();
            }
        });

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
        start(scenario, new Handler.Processor()
        {
            @Override
            public void process(org.eclipse.jetty.server.Request request, org.eclipse.jetty.server.Response response, Callback callback)
            {
                response.write(true, ByteBuffer.allocate(1024), callback);
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
        startServer(scenario, new EmptyServerHandler());

        AtomicBoolean open = new AtomicBoolean();
        ClientConnector clientConnector = new ClientConnector();
        clientConnector.setSslContextFactory(scenario.newClientSslContextFactory());
        client = new HttpClient(new HttpClientTransportOverHTTP(clientConnector)
        {
            @Override
            public org.eclipse.jetty.io.Connection newConnection(EndPoint endPoint, Map<String, Object> context)
            {
                return new HttpConnectionOverHTTP(endPoint, context)
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
                output.write(httpResponse.getBytes(UTF_8));
                output.flush();

                ContentResponse response = listener.get(5, TimeUnit.SECONDS);
                assertEquals(200, response.getStatus());
                assertThat(connection, Matchers.instanceOf(HttpConnectionOverHTTP.class));
                HttpConnectionOverHTTP httpConnection = (HttpConnectionOverHTTP)connection;
                EndPoint endPoint = httpConnection.getEndPoint();
                assertTrue(endPoint.isOpen());

                // After a CONNECT+200, this connection is in "tunnel mode",
                // and applications that want to deal with tunnel bytes must
                // likely access the underlying EndPoint.
                // For the purpose of this test, we just re-enable fill interest
                // so that we can send another clear-text HTTP request.
                httpConnection.fillInterested();

                request = client.newRequest(host, port);
                listener = new FutureResponseListener(request);
                connection.send(request, listener);

                consume(input, false);

                httpResponse =
                    "HTTP/1.1 200 OK\r\n" +
                        "Content-Length: 0\r\n" +
                        "\r\n";
                output.write(httpResponse.getBytes(UTF_8));
                output.flush();

                listener.get(5, TimeUnit.SECONDS);
            }
        }
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testIPv6HostWithHTTP10(Scenario scenario) throws Exception
    {
        Assumptions.assumeTrue(Net.isIpv6InterfaceAvailable());
        start(scenario, new Handler.Processor()
        {
            @Override
            public void process(org.eclipse.jetty.server.Request request, org.eclipse.jetty.server.Response response, Callback callback)
            {
                response.getHeaders().put(HttpHeader.CONTENT_TYPE, "text/plain");
                Content.Sink.write(response, true, org.eclipse.jetty.server.Request.getServerName(request), callback);
            }
        });

        URI uri = URI.create(scenario.getScheme() + "://[::1]:" + connector.getLocalPort() + "/path");
        ContentResponse response = client.newRequest(uri)
            .method(HttpMethod.PUT)
            .version(HttpVersion.HTTP_1_0)
            .onRequestBegin(r -> r.headers(headers -> headers.remove(HttpHeader.HOST)))
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertNotNull(response);
        assertEquals(200, response.getStatus());
        String content = response.getContentAsString();
        assertThat(content, Matchers.startsWith("["));
        assertThat(content, Matchers.endsWith(":1]"));
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testCopyRequest(Scenario scenario) throws Exception
    {
        startClient(scenario);

        assertCopyRequest(client.newRequest("http://example.com/some/url")
            .method(HttpMethod.HEAD)
            .version(HttpVersion.HTTP_2)
            .body(new StringRequestContent("some string"))
            .timeout(321, TimeUnit.SECONDS)
            .idleTimeout(2221, TimeUnit.SECONDS)
            .followRedirects(true)
            .headers(headers -> headers.put(HttpHeader.CONTENT_TYPE, "application/json"))
            .headers(headers -> headers.put("X-Some-Custom-Header", "some-value")));

        assertCopyRequest(client.newRequest("https://example.com")
            .method(HttpMethod.POST)
            .version(HttpVersion.HTTP_1_0)
            .body(new StringRequestContent("some other string"))
            .timeout(123231, TimeUnit.SECONDS)
            .idleTimeout(232342, TimeUnit.SECONDS)
            .followRedirects(false)
            .tag("tag")
            .headers(headers -> headers.put(HttpHeader.ACCEPT, "application/json"))
            .headers(headers -> headers.put("X-Some-Other-Custom-Header", "some-other-value")));

        assertCopyRequest(client.newRequest("https://example.com")
            .headers(headers -> headers.add(HttpHeader.ACCEPT, "application/json"))
            .headers(headers -> headers.add(HttpHeader.ACCEPT, "application/xml"))
            .headers(headers -> headers.add("x-same-name", "value1"))
            .headers(headers -> headers.add("x-same-name", "value2")));

        assertCopyRequest(client.newRequest("https://example.com")
            .headers(headers -> headers.put(HttpHeader.ACCEPT, "application/json"))
            .headers(headers -> headers.put(HttpHeader.CONTENT_TYPE, "application/json")));

        assertCopyRequest(client.newRequest("https://example.com")
            .headers(headers -> headers.put("Accept", "application/json"))
            .headers(headers -> headers.put("Content-Type", "application/json")));

        assertCopyRequest(client.newRequest("https://example.com")
            .headers(headers -> headers.put("X-Custom-Header-1", "value1"))
            .headers(headers -> headers.put("X-Custom-Header-2", "value2")));

        assertCopyRequest(client.newRequest("https://example.com")
            .headers(headers -> headers.put("X-Custom-Header-1", "value"))
            .headers(headers -> headers.put("X-Custom-Header-2", "value")));
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testHostWithHTTP10(Scenario scenario) throws Exception
    {
        start(scenario, new EmptyServerHandler()
        {
            @Override
            protected void service(org.eclipse.jetty.server.Request request, org.eclipse.jetty.server.Response response)
            {
                assertThat(request.getHeaders().get("Host"), Matchers.notNullValue());
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
                output.write(httpResponse.getBytes(UTF_8));
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
                output.write(httpResponse.getBytes(UTF_8));
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
        start(scenario, new Handler.Processor()
        {
            @Override
            public void process(org.eclipse.jetty.server.Request request, org.eclipse.jetty.server.Response response, Callback callback)
            {
                response.write(true, ByteBuffer.wrap(bytes), callback);
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

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testUnsolicitedResponseBytesFromServer(Scenario scenario) throws Exception
    {
        String response = "" +
            "HTTP/1.1 408 Request Timeout\r\n" +
            "Content-Length: 0\r\n" +
            "Connection: close\r\n" +
            "\r\n";
        testUnsolicitedBytesFromServer(scenario, response);
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testUnsolicitedInvalidBytesFromServer(Scenario scenario) throws Exception
    {
        String response = "ABCDEF";
        testUnsolicitedBytesFromServer(scenario, response);
    }

    private void testUnsolicitedBytesFromServer(Scenario scenario, String bytesFromServer) throws Exception
    {
        try (ServerSocket server = new ServerSocket(0))
        {
            startClient(scenario, clientConnector ->
            {
                clientConnector.setSelectors(1);
                HttpClientTransportOverHTTP transport = new HttpClientTransportOverHTTP(clientConnector);
                transport.setConnectionPoolFactory(destination ->
                {
                    ConnectionPool connectionPool = new DuplexConnectionPool(destination, 1, destination);
                    connectionPool.preCreateConnections(1);
                    return connectionPool;
                });
                return transport;
            }, null);

            String host = "localhost";
            int port = server.getLocalPort();

            // Resolve the destination which will pre-create a connection.
            HttpDestination destination = client.resolveDestination(new Origin("http", host, port));

            // Accept the connection and send an unsolicited 408.
            try (Socket socket = server.accept())
            {
                OutputStream output = socket.getOutputStream();
                output.write(bytesFromServer.getBytes(UTF_8));
                output.flush();
            }

            // Give some time to the client to process the response.
            Thread.sleep(1000);

            AbstractConnectionPool pool = (AbstractConnectionPool)destination.getConnectionPool();
            assertEquals(0, pool.getConnectionCount());
        }
    }

    // TODO: move this test to Servlet tests.
/*
    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testHttpParserCloseWithAsyncReads(Scenario scenario) throws Exception
    {
        CountDownLatch serverOnErrorLatch = new CountDownLatch(1);

        start(scenario, new AbstractHandler()
        {
            @Override
            public void handle(String target, org.eclipse.jetty.server.Request jettyRequest, HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                jettyRequest.setHandled(true);
                if (request.getDispatcherType() != DispatcherType.REQUEST)
                    return;

                AsyncContext asyncContext = request.startAsync();
                asyncContext.setTimeout(2000); // allow async to timeout
                ServletInputStream input = request.getInputStream();
                input.setReadListener(new ReadListener()
                {
                    @Override
                    public void onDataAvailable() throws IOException
                    {
                        while (input.isReady())
                        {
                            int read = input.read();
                            if (read < 0)
                                break;
                        }
                    }

                    @Override
                    public void onAllDataRead() throws IOException
                    {
                    }

                    @Override
                    public void onError(Throwable t)
                    {
                        asyncContext.complete();
                        serverOnErrorLatch.countDown();
                    }
                });
                // Close the parser to cause the issue.
                org.eclipse.jetty.server.HttpConnection.getCurrentConnection().getParser().close();
            }
        });
        server.start();

        int length = 16;
        ByteBuffer chunk1 = ByteBuffer.allocate(length / 2);
        AsyncRequestContent content = new AsyncRequestContent(chunk1)
        {
            @Override
            public long getLength()
            {
                return length;
            }
        };
        CountDownLatch clientResultLatch = new CountDownLatch(1);
        client.newRequest("localhost", connector.getLocalPort())
            .scheme(scenario.getScheme())
            .method(HttpMethod.POST)
            .body(content)
            .send(result -> clientResultLatch.countDown());

        Thread.sleep(1000);

        ByteBuffer chunk2 = ByteBuffer.allocate(length / 2);
        content.offer(chunk2);
        content.close();

        assertTrue(clientResultLatch.await(5, TimeUnit.SECONDS), "clientResultLatch didn't finish");
        assertTrue(serverOnErrorLatch.await(5, TimeUnit.SECONDS), "serverOnErrorLatch didn't finish");
    }
*/
    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testBindAddress(Scenario scenario) throws Exception
    {
        String bindAddress = "127.0.0.2";
        start(scenario, new EmptyServerHandler()
        {
            @Override
            protected void service(org.eclipse.jetty.server.Request request, org.eclipse.jetty.server.Response response)
            {
                assertEquals(bindAddress, org.eclipse.jetty.server.Request.getRemoteAddr(request));
            }
        });

        client.setBindAddress(new InetSocketAddress(bindAddress, 0));

        CountDownLatch latch = new CountDownLatch(1);
        ContentResponse response = client.newRequest("localhost", connector.getLocalPort())
            .scheme(scenario.getScheme())
            .path("/1")
            .onRequestBegin(r ->
            {
                client.newRequest("localhost", connector.getLocalPort())
                    .scheme(scenario.getScheme())
                    .path("/2")
                    .send(result ->
                    {
                        assertTrue(result.isSucceeded());
                        assertEquals(HttpStatus.OK_200, result.getResponse().getStatus());
                        latch.countDown();
                    });
            })
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertEquals(HttpStatus.OK_200, response.getStatus());
    }

    private void assertCopyRequest(Request original)
    {
        Request copy = client.copyRequest((HttpRequest)original, original.getURI());
        assertEquals(original.getURI(), copy.getURI());
        assertEquals(original.getMethod(), copy.getMethod());
        assertEquals(original.getVersion(), copy.getVersion());
        assertEquals(original.getBody(), copy.getBody());
        assertEquals(original.getIdleTimeout(), copy.getIdleTimeout());
        assertEquals(original.getTimeout(), copy.getTimeout());
        assertEquals(original.isFollowRedirects(), copy.isFollowRedirects());
        assertEquals(original.getTag(), copy.getTag());
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
                .body(new StringRequestContent("0123456789ABCDEF"))
                .send(this);
        }
    }
}
