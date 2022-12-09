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

package org.eclipse.jetty.test.client.transport;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

import org.eclipse.jetty.client.HttpDestination;
import org.eclipse.jetty.client.Origin;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Destination;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.client.api.Result;
import org.eclipse.jetty.client.util.BytesRequestContent;
import org.eclipse.jetty.client.util.FutureResponseListener;
import org.eclipse.jetty.client.util.InputStreamResponseListener;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.http2.FlowControlStrategy;
import org.eclipse.jetty.http3.client.transport.HttpClientTransportOverHTTP3;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.NetworkConnector;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.SecureRequestCustomizer;
import org.eclipse.jetty.toolchain.test.Net;
import org.eclipse.jetty.util.Blocker;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.IteratingCallback;
import org.eclipse.jetty.util.IteratingNestedCallback;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

public class HttpClientTest extends AbstractTest
{
    @ParameterizedTest
    @MethodSource("transports")
    public void testRequestWithoutResponseContent(Transport transport) throws Exception
    {
        final int status = HttpStatus.NO_CONTENT_204;
        start(transport, new Handler.Abstract()
        {
            @Override
            public void process(Request request, org.eclipse.jetty.server.Response response, Callback callback)
            {
                response.setStatus(status);
                callback.succeeded();
            }
        });

        ContentResponse response = client.newRequest(newURI(transport))
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertEquals(status, response.getStatus());
        assertEquals(0, response.getContent().length);
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testRequestWithSmallResponseContent(Transport transport) throws Exception
    {
        testRequestWithResponseContent(transport, 1024);
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testRequestWithLargeResponseContent(Transport transport) throws Exception
    {
        testRequestWithResponseContent(transport, 1024 * 1024);
    }

    private void testRequestWithResponseContent(Transport transport, int length) throws Exception
    {
        final byte[] bytes = new byte[length];
        new Random().nextBytes(bytes);
        start(transport, new Handler.Abstract()
        {
            @Override
            public void process(Request request, org.eclipse.jetty.server.Response response, Callback callback)
            {
                response.getHeaders().putLongField(HttpHeader.CONTENT_LENGTH, length);
                response.write(true, ByteBuffer.wrap(bytes), callback);
            }
        });

        org.eclipse.jetty.client.api.Request request = client.newRequest(newURI(transport));
        FutureResponseListener listener = new FutureResponseListener(request, length);
        request.timeout(10, TimeUnit.SECONDS).send(listener);
        ContentResponse response = listener.get();

        assertEquals(200, response.getStatus());
        assertArrayEquals(bytes, response.getContent());
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testRequestWithSmallResponseContentChunked(Transport transport) throws Exception
    {
        testRequestWithResponseContentChunked(transport, 512);
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testRequestWithLargeResponseContentChunked(Transport transport) throws Exception
    {
        testRequestWithResponseContentChunked(transport, 512 * 512);
    }

    private void testRequestWithResponseContentChunked(Transport transport, int length) throws Exception
    {
        final byte[] chunk1 = new byte[length];
        final byte[] chunk2 = new byte[length];
        Random random = new Random();
        random.nextBytes(chunk1);
        random.nextBytes(chunk2);
        byte[] bytes = new byte[chunk1.length + chunk2.length];
        System.arraycopy(chunk1, 0, bytes, 0, chunk1.length);
        System.arraycopy(chunk2, 0, bytes, chunk1.length, chunk2.length);
        start(transport, new Handler.Abstract()
        {
            @Override
            public void process(Request request, org.eclipse.jetty.server.Response response, Callback callback) throws Exception
            {
                Content.Sink.write(response, false, ByteBuffer.wrap(chunk1));
                response.write(true, ByteBuffer.wrap(chunk2), callback);
            }
        });

        org.eclipse.jetty.client.api.Request request = client.newRequest(newURI(transport));
        FutureResponseListener listener = new FutureResponseListener(request, 2 * length);
        request.timeout(10, TimeUnit.SECONDS).send(listener);
        ContentResponse response = listener.get();

        assertEquals(200, response.getStatus());
        assertArrayEquals(bytes, response.getContent());
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testUploadZeroLengthWithoutResponseContent(Transport transport) throws Exception
    {
        testUploadWithoutResponseContent(transport, 0);
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testUploadSmallWithoutResponseContent(Transport transport) throws Exception
    {
        testUploadWithoutResponseContent(transport, 1024);
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testUploadLargeWithoutResponseContent(Transport transport) throws Exception
    {
        testUploadWithoutResponseContent(transport, 1024 * 1024);
    }

    private void testUploadWithoutResponseContent(Transport transport, int length) throws Exception
    {
        final byte[] bytes = new byte[length];
        new Random().nextBytes(bytes);
        start(transport, new Handler.Abstract()
        {
            @Override
            public void process(Request request, org.eclipse.jetty.server.Response response, Callback callback) throws Exception
            {
                InputStream input = Request.asInputStream(request);
                for (byte b : bytes)
                {
                    Assertions.assertEquals(b & 0xFF, input.read());
                }
                Assertions.assertEquals(-1, input.read());
                callback.succeeded();
            }
        });

        ContentResponse response = client.newRequest(newURI(transport))
            .method(HttpMethod.POST)
            .body(new BytesRequestContent(bytes))
            .timeout(15, TimeUnit.SECONDS)
            .send();

        assertEquals(200, response.getStatus());
        assertEquals(0, response.getContent().length);
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testClientManyWritesSlowServer(Transport transport) throws Exception
    {
        start(transport, new Handler.Abstract()
        {
            @Override
            public void process(Request request, org.eclipse.jetty.server.Response response, Callback callback) throws Exception
            {
                long sleep = 1024;
                long total = 0;
                while (true)
                {
                    Content.Chunk chunk = request.read();
                    if (chunk == null)
                    {
                        try (Blocker.Runnable blocker = Blocker.runnable())
                        {
                            request.demand(blocker);
                            blocker.block();
                            continue;
                        }
                    }
                    if (chunk instanceof Content.Chunk.Error error)
                        throw IO.rethrow(error.getCause());

                    total += chunk.remaining();
                    if (total >= sleep)
                    {
                        sleep(250);
                        sleep += 256;
                    }
                    chunk.release();
                    if (chunk.isLast())
                        break;
                }
                Content.Sink.write(response, true, String.valueOf(total), callback);
            }
        });

        int chunks = 256;
        int chunkSize = 16;
        byte[][] bytes = IntStream.range(0, chunks).mapToObj(x -> new byte[chunkSize]).toArray(byte[][]::new);
        BytesRequestContent content = new BytesRequestContent("application/octet-stream", bytes);
        ContentResponse response = client.newRequest(newURI(transport))
            .method(HttpMethod.POST)
            .body(content)
            .timeout(15, TimeUnit.SECONDS)
            .send();

        assertEquals(HttpStatus.OK_200, response.getStatus());
        assertEquals(chunks * chunkSize, Integer.parseInt(response.getContentAsString()));
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testRequestAfterFailedRequest(Transport transport) throws Exception
    {
        int length = FlowControlStrategy.DEFAULT_WINDOW_SIZE;
        start(transport, new Handler.Abstract()
        {
            @Override
            public void process(Request request, org.eclipse.jetty.server.Response response, Callback callback)
            {
                response.write(true, ByteBuffer.allocate(length), callback);
            }
        });

        // Make a request with a large enough response buffer.
        org.eclipse.jetty.client.api.Request request = client.newRequest(newURI(transport));
        FutureResponseListener listener = new FutureResponseListener(request, length);
        request.send(listener);
        ContentResponse response = listener.get(15, TimeUnit.SECONDS);
        assertEquals(response.getStatus(), 200);

        // Make a request with a small response buffer, should fail.
        try
        {
            request = client.newRequest(newURI(transport));
            listener = new FutureResponseListener(request, length / 10);
            request.send(listener);
            listener.get(15, TimeUnit.SECONDS);
            fail("Expected ExecutionException");
        }
        catch (ExecutionException x)
        {
            assertThat(x.getMessage(), containsString("exceeded"));
        }

        // Verify that we can make another request.
        request = client.newRequest(newURI(transport));
        listener = new FutureResponseListener(request, length);
        request.send(listener);
        response = listener.get(15, TimeUnit.SECONDS);
        assertEquals(response.getStatus(), 200);
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testClientCannotValidateServerCertificate(Transport transport) throws Exception
    {
        // Only run this test for transports over TLS.
        assumeTrue(transport.isSecure());

        start(transport, new EmptyServerHandler());
        // Disable validations on the server to be sure
        // that the test failure happens during the
        // validation of the certificate on the client.
        httpConfig.getCustomizer(SecureRequestCustomizer.class).setSniHostCheck(false);

        // Use a SslContextFactory.Client that verifies server certificates,
        // requests should fail because the server certificate is unknown.
        SslContextFactory.Client clientTLS = newSslContextFactoryClient();
        clientTLS.setEndpointIdentificationAlgorithm("HTTPS");
        client.stop();
        client.setSslContextFactory(clientTLS);
        client.start();
        if (transport == Transport.H3)
        {
            assumeTrue(false, "certificate verification not yet supported in quic");
            // TODO: the lines below should be enough, but they don't work. To be investigated.
            HttpClientTransportOverHTTP3 http3Transport = (HttpClientTransportOverHTTP3)client.getTransport();
            http3Transport.getHTTP3Client().getQuicConfiguration().setVerifyPeerCertificates(true);
        }

        assertThrows(ExecutionException.class, () ->
        {
            // Use an IP address not present in the certificate.
            int serverPort = newURI(transport).getPort();
            client.newRequest("https://127.0.0.2:" + serverPort)
                .timeout(5, TimeUnit.SECONDS)
                .send();
        });
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testOPTIONS(Transport transport) throws Exception
    {
        start(transport, new Handler.Abstract()
        {
            @Override
            public void process(Request request, org.eclipse.jetty.server.Response response, Callback callback)
            {
                assertTrue(HttpMethod.OPTIONS.is(request.getMethod()));
                assertEquals("*", Request.getPathInContext(request));
                callback.succeeded();
            }
        });

        ContentResponse response = client.newRequest(newURI(transport))
            .method(HttpMethod.OPTIONS)
            .path("*")
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertEquals(HttpStatus.OK_200, response.getStatus());
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testOPTIONSWithRelativeRedirect(Transport transport) throws Exception
    {
        start(transport, new Handler.Abstract()
        {
            @Override
            public void process(Request request, org.eclipse.jetty.server.Response response, Callback callback)
            {
                if ("*".equals(Request.getPathInContext(request)))
                {
                    // Be nasty and send a relative redirect.
                    // Code 303 will change the method to GET.
                    response.setStatus(HttpStatus.SEE_OTHER_303);
                    response.getHeaders().put(HttpHeader.LOCATION, "/");
                }
                callback.succeeded();
            }
        });

        ContentResponse response = client.newRequest(newURI(transport))
            .method(HttpMethod.OPTIONS)
            .path("*")
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertEquals(HttpStatus.OK_200, response.getStatus());
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testDownloadWithInputStreamResponseListener(Transport transport) throws Exception
    {
        String content = "hello world";
        start(transport, new Handler.Abstract()
        {
            @Override
            public void process(Request request, org.eclipse.jetty.server.Response response, Callback callback)
            {
                Content.Sink.write(response, true, content, callback);
            }
        });

        CountDownLatch latch = new CountDownLatch(1);
        InputStreamResponseListener listener = new InputStreamResponseListener();
        client.newRequest(newURI(transport))
            .onResponseSuccess(response -> latch.countDown())
            .send(listener);
        Response response = listener.get(5, TimeUnit.SECONDS);
        assertEquals(200, response.getStatus());

        // Response cannot succeed until we read the content.
        assertFalse(latch.await(500, TimeUnit.MILLISECONDS));

        InputStream input = listener.getInputStream();
        assertEquals(content, IO.toString(input));

        assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testConnectionListener(Transport transport) throws Exception
    {
        start(transport, new EmptyServerHandler());
        long idleTimeout = 1000;
        client.setIdleTimeout(idleTimeout);

        CountDownLatch openLatch = new CountDownLatch(1);
        CountDownLatch closeLatch = new CountDownLatch(1);
        client.addBean(new org.eclipse.jetty.io.Connection.Listener()
        {
            @Override
            public void onOpened(org.eclipse.jetty.io.Connection connection)
            {
                openLatch.countDown();
            }

            @Override
            public void onClosed(org.eclipse.jetty.io.Connection connection)
            {
                closeLatch.countDown();
            }
        });

        ContentResponse response = client.newRequest(newURI(transport))
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertEquals(HttpStatus.OK_200, response.getStatus());
        assertTrue(openLatch.await(1, TimeUnit.SECONDS));

        Thread.sleep(2 * idleTimeout);
        assertTrue(closeLatch.await(1, TimeUnit.SECONDS));
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testAsyncResponseContentBackPressure(Transport transport) throws Exception
    {
        start(transport, new Handler.Abstract()
        {
            @Override
            public void process(Request request, org.eclipse.jetty.server.Response response, Callback callback)
            {
                // Large write to generate multiple DATA frames.
                response.write(true, ByteBuffer.allocate(256 * 1024), callback);
            }
        });

        CountDownLatch completeLatch = new CountDownLatch(1);
        AtomicInteger counter = new AtomicInteger();
        AtomicReference<Callback> callbackRef = new AtomicReference<>();
        AtomicReference<CountDownLatch> latchRef = new AtomicReference<>(new CountDownLatch(1));
        client.newRequest(newURI(transport))
            .onResponseContentAsync((response, content, callback) ->
            {
                if (counter.incrementAndGet() == 1)
                {
                    callbackRef.set(callback);
                    latchRef.get().countDown();
                }
                else
                {
                    callback.succeeded();
                }
            })
            .send(result -> completeLatch.countDown());

        assertTrue(latchRef.get().await(5, TimeUnit.SECONDS));
        // Wait some time to verify that back pressure is applied correctly.
        Thread.sleep(1000);
        assertEquals(1, counter.get());
        callbackRef.get().succeeded();

        assertTrue(completeLatch.await(5, TimeUnit.SECONDS));
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testResponseWithContentCompleteListenerInvokedOnce(Transport transport) throws Exception
    {
        start(transport, new Handler.Abstract()
        {
            @Override
            public void process(Request request, org.eclipse.jetty.server.Response response, Callback callback)
            {
                Content.Sink.write(response, true, "Jetty", callback);
            }
        });

        AtomicInteger completes = new AtomicInteger();
        client.newRequest(newURI(transport))
            .send(result -> completes.incrementAndGet());

        sleep(1000);

        assertEquals(1, completes.get());
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testHEADResponds200(Transport transport) throws Exception
    {
        testHEAD(transport, "/", HttpStatus.OK_200);
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testHEADResponds404(Transport transport) throws Exception
    {
        testHEAD(transport, "/notMapped", HttpStatus.NOT_FOUND_404);
    }

    private void testHEAD(Transport transport, String path, int status) throws Exception
    {
        byte[] data = new byte[1024];
        new Random().nextBytes(data);
        start(transport, new Handler.Abstract()
        {
            @Override
            public void process(Request request, org.eclipse.jetty.server.Response response, Callback callback)
            {
                String target = Request.getPathInContext(request);
                if ("/notMapped".equals(target))
                    org.eclipse.jetty.server.Response.writeError(request, response, callback, HttpStatus.NOT_FOUND_404);
                else
                    response.write(true, ByteBuffer.wrap(data), callback);
            }
        });

        ContentResponse response = client.newRequest(newURI(transport))
            .method(HttpMethod.HEAD)
            .path(path)
            .send();

        assertEquals(status, response.getStatus());
        assertEquals(0, response.getContent().length);
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testHEADWithAcceptHeaderAndSendError(Transport transport) throws Exception
    {
        int status = HttpStatus.BAD_REQUEST_400;
        start(transport, new Handler.Abstract()
        {
            @Override
            public void process(Request request, org.eclipse.jetty.server.Response response, Callback callback)
            {
                org.eclipse.jetty.server.Response.writeError(request, response, callback, status);
            }
        });

        ContentResponse response = client.newRequest(newURI(transport))
            .method(HttpMethod.HEAD)
            .headers(headers -> headers.put(HttpHeader.ACCEPT, "*/*"))
            .send();

        assertEquals(status, response.getStatus());
        assertEquals(0, response.getContent().length);
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testHEADWithContentLengthGreaterThanMaxBufferingCapacity(Transport transport) throws Exception
    {
        int length = 1024;
        start(transport, new Handler.Abstract()
        {
            @Override
            public void process(Request request, org.eclipse.jetty.server.Response response, Callback callback)
            {
                response.getHeaders().putLongField(HttpHeader.CONTENT_LENGTH, length);
                response.write(true, ByteBuffer.allocate(length), callback);
            }
        });

        org.eclipse.jetty.client.api.Request request = client.newRequest(newURI(transport))
            .method(HttpMethod.HEAD);
        FutureResponseListener listener = new FutureResponseListener(request, length / 2);
        request.send(listener);
        ContentResponse response = listener.get(5, TimeUnit.SECONDS);

        assertEquals(HttpStatus.OK_200, response.getStatus());
        assertEquals(0, response.getContent().length);
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testOneDestinationPerUser(Transport transport) throws Exception
    {
        start(transport, new EmptyServerHandler());
        int runs = 4;
        int users = 16;
        for (int i = 0; i < runs; ++i)
        {
            for (int j = 0; j < users; ++j)
            {
                ContentResponse response = client.newRequest(newURI(transport))
                    .tag(j)
                    .send();
                assertEquals(HttpStatus.OK_200, response.getStatus());
            }
        }

        List<Destination> destinations = client.getDestinations();
        assertEquals(users, destinations.size());
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testIPv6Host(Transport transport) throws Exception
    {
        assumeTrue(Net.isIpv6InterfaceAvailable());
        assumeTrue(transport != Transport.UNIX_DOMAIN);

        start(transport, new Handler.Abstract()
        {
            @Override
            public void process(Request request, org.eclipse.jetty.server.Response response, Callback callback)
            {
                response.getHeaders().put(HttpHeader.CONTENT_TYPE, "text/plain");
                Content.Sink.write(response, true, request.getHeaders().get(HttpHeader.HOST), callback);
            }
        });

        // Test with a full URI.
        String hostAddress = "::1";
        String host = "[" + hostAddress + "]";
        URI serverURI = newURI(transport);
        String uri = serverURI.toString().replace("localhost", host) + "/path";
        ContentResponse response = client.newRequest(uri)
            .method(HttpMethod.PUT)
            .timeout(5, TimeUnit.SECONDS)
            .send();
        assertNotNull(response);
        assertEquals(200, response.getStatus());
        assertThat(new String(response.getContent(), StandardCharsets.ISO_8859_1), Matchers.startsWith("[::1]:"));

        // Test with host address.
        int port = serverURI.getPort();
        response = client.newRequest(hostAddress, port)
            .scheme(serverURI.getScheme())
            .method(HttpMethod.PUT)
            .timeout(5, TimeUnit.SECONDS)
            .send();
        assertNotNull(response);
        assertEquals(200, response.getStatus());
        assertThat(new String(response.getContent(), StandardCharsets.ISO_8859_1), Matchers.startsWith("[::1]:"));

        // Test with host name.
        response = client.newRequest(host, port)
            .scheme(serverURI.getScheme())
            .method(HttpMethod.PUT)
            .timeout(5, TimeUnit.SECONDS)
            .send();
        assertNotNull(response);
        assertEquals(200, response.getStatus());
        assertThat(new String(response.getContent(), StandardCharsets.ISO_8859_1), Matchers.startsWith("[::1]:"));

        Assertions.assertEquals(1, client.getDestinations().size());
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testRequestWithDifferentDestination(Transport transport) throws Exception
    {
        assumeTrue(transport != Transport.UNIX_DOMAIN);

        String requestScheme = newURI(transport).getScheme();
        String requestHost = "otherHost.com";
        int requestPort = 8888;
        start(transport, new Handler.Abstract()
        {
            @Override
            public void process(Request request, org.eclipse.jetty.server.Response response, Callback callback)
            {
                HttpURI uri = request.getHttpURI();
                assertEquals(requestHost, uri.getHost());
                assertEquals(requestPort, uri.getPort());
                if (transport == Transport.H2C || transport == Transport.H2)
                    assertEquals(requestScheme, uri.getScheme());
                callback.succeeded();
            }
        });
        if (transport.isSecure())
            httpConfig.getCustomizer(SecureRequestCustomizer.class).setSniHostCheck(false);

        Origin origin = new Origin(requestScheme, "localhost", ((NetworkConnector)connector).getLocalPort());
        HttpDestination destination = client.resolveDestination(origin);

        var request = client.newRequest(requestHost, requestPort)
            .scheme(requestScheme)
            .path("/path");

        CountDownLatch resultLatch = new CountDownLatch(1);
        destination.send(request, result ->
        {
            assertTrue(result.isSucceeded());
            assertEquals(HttpStatus.OK_200, result.getResponse().getStatus());
            resultLatch.countDown();
        });

        assertTrue(resultLatch.await(5, TimeUnit.SECONDS));
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testRequestIdleTimeout(Transport transport) throws Exception
    {
        CountDownLatch latch = new CountDownLatch(1);
        long idleTimeout = 500;
        start(transport, new Handler.Abstract()
        {
            @Override
            public void process(Request request, org.eclipse.jetty.server.Response response, Callback callback) throws Exception
            {
                String target = Request.getPathInContext(request);
                if (target.equals("/1"))
                    assertTrue(latch.await(5, TimeUnit.SECONDS));
                else if (target.equals("/2"))
                    Thread.sleep(2 * idleTimeout);
                else
                    fail("Unknown path: " + target);
                callback.succeeded();
            }
        });

        assertThrows(TimeoutException.class, () ->
            client.newRequest(newURI(transport))
                .path("/1")
                .idleTimeout(idleTimeout, TimeUnit.MILLISECONDS)
                .timeout(2 * idleTimeout, TimeUnit.MILLISECONDS)
                .send());
        latch.countDown();

        // Make another request without specifying the idle timeout, should not fail
        ContentResponse response = client.newRequest(newURI(transport))
            .path("/2")
            .timeout(3 * idleTimeout, TimeUnit.MILLISECONDS)
            .send();

        assertNotNull(response);
        assertEquals(200, response.getStatus());
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testContentSourceListeners(Transport transport) throws Exception
    {
        int totalBytes = 1024;
        start(transport, new TestProcessor(totalBytes));

        List<Content.Chunk> chunks = new CopyOnWriteArrayList<>();
        CompleteContentSourceListener listener = new CompleteContentSourceListener()
        {
            @Override
            public void onContentSource(Response response, Content.Source contentSource)
            {
                accumulateChunks(response, contentSource, chunks);
            }
        };
        client.newRequest(newURI(transport))
            .path("/")
            .send(listener);
        listener.await(5, TimeUnit.SECONDS);

        assertThat(listener.result.getResponse().getStatus(), is(200));
        assertThat(chunks.stream().mapToInt(c -> c.getByteBuffer().remaining()).sum(), is(totalBytes));
        assertThat(chunks.get(chunks.size() - 1).isLast(), is(true));
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testContentSourceListenersFailure(Transport transport) throws Exception
    {
        int totalBytes = 1024;
        start(transport, new TestProcessor(totalBytes));

        CompleteContentSourceListener listener = new CompleteContentSourceListener()
        {
            @Override
            public void onContentSource(Response response, Content.Source contentSource)
            {
                contentSource.fail(new Exception("Synthetic Failure"));
            }
        };
        client.newRequest(newURI(transport))
            .path("/")
            .send(listener);
        listener.await(5, TimeUnit.SECONDS);

        assertThat(listener.result.isFailed(), is(true));
        assertThat(listener.result.getFailure().getMessage(), is("Synthetic Failure"));
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testContentSourceListenerDemandInSpawnedThread(Transport transport) throws Exception
    {
        int totalBytes = 1024;
        start(transport, new TestProcessor(totalBytes));

        List<Content.Chunk> chunks = new CopyOnWriteArrayList<>();
        CompleteContentSourceListener listener = new CompleteContentSourceListener()
        {
            @Override
            public void onContentSource(Response response, Content.Source contentSource)
            {
                new Thread(() -> accumulateChunksInSpawnedThread(response, contentSource, chunks))
                    .start();
            }
        };
        client.newRequest(newURI(transport))
            .path("/")
            .send(listener);
        listener.await(5, TimeUnit.SECONDS);

        assertThat(listener.result.getResponse().getStatus(), is(200));
        assertThat(chunks.stream().mapToInt(c -> c.getByteBuffer().remaining()).sum(), is(totalBytes));
        assertThat(chunks.get(chunks.size() - 1).isLast(), is(true));
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testParallelContentSourceListeners(Transport transport) throws Exception
    {
        int totalBytes = 1024;
        start(transport, new TestProcessor(totalBytes));

        List<Content.Chunk> chunks1 = new CopyOnWriteArrayList<>();
        List<Content.Chunk> chunks2 = new CopyOnWriteArrayList<>();
        List<Content.Chunk> chunks3 = new CopyOnWriteArrayList<>();

        ContentResponse resp = client.newRequest(newURI(transport))
            .path("/")
            .onResponseContentSource((response, contentSource) -> accumulateChunks(response, contentSource, chunks1))
            .onResponseContentSource((response, contentSource) -> accumulateChunks(response, contentSource, chunks2))
            .onResponseContentSource((response, contentSource) -> accumulateChunks(response, contentSource, chunks3))
            .send();

        assertThat(resp.getStatus(), is(200));
        assertThat(resp.getContent().length, is(totalBytes));

        assertThat(chunks1.stream().mapToInt(c -> c.getByteBuffer().remaining()).sum(), is(totalBytes));
        assertThat(chunks1.get(chunks1.size() - 1).isLast(), is(true));
        assertThat(chunks2.stream().mapToInt(c -> c.getByteBuffer().remaining()).sum(), is(totalBytes));
        assertThat(chunks2.get(chunks2.size() - 1).isLast(), is(true));
        assertThat(chunks3.stream().mapToInt(c -> c.getByteBuffer().remaining()).sum(), is(totalBytes));
        assertThat(chunks3.get(chunks3.size() - 1).isLast(), is(true));
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testParallelContentSourceListenersPartialFailure(Transport transport) throws Exception
    {
        int totalBytes = 1024;
        start(transport, new TestProcessor(totalBytes));

        List<Content.Chunk> chunks1 = new CopyOnWriteArrayList<>();
        List<Content.Chunk> chunks2 = new CopyOnWriteArrayList<>();
        List<Content.Chunk> chunks3 = new CopyOnWriteArrayList<>();
        ContentResponse contentResponse = client.newRequest(newURI(transport))
            .path("/")
            .onResponseContentSource((response, contentSource) -> accumulateChunks(response, contentSource, chunks1))
            .onResponseContentSource((response, contentSource) -> accumulateChunks(response, contentSource, chunks2))
            .onResponseContentSource((response, contentSource) ->
            {
                contentSource.fail(new Exception("Synthetic Failure"));
                contentSource.demand(() -> chunks3.add(contentSource.read()));
            })
            .send();
        assertThat(contentResponse.getStatus(), is(200));
        assertThat(contentResponse.getContent().length, is(totalBytes));

        assertThat(chunks1.stream().mapToInt(c -> c.getByteBuffer().remaining()).sum(), is(totalBytes));
        assertThat(chunks2.stream().mapToInt(c -> c.getByteBuffer().remaining()).sum(), is(totalBytes));
        assertThat(chunks3.stream().mapToInt(c -> c.getByteBuffer().remaining()).sum(), is(0));
        assertThat(chunks3.size(), is(1));
        assertThat(chunks3.get(0), instanceOf(Content.Chunk.Error.class));
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testParallelContentSourceListenersPartialFailureInSpawnedThread(Transport transport) throws Exception
    {
        int totalBytes = 1024;
        start(transport, new TestProcessor(totalBytes));

        List<Content.Chunk> chunks1 = new CopyOnWriteArrayList<>();
        List<Content.Chunk> chunks2 = new CopyOnWriteArrayList<>();
        List<Content.Chunk> chunks3 = new CopyOnWriteArrayList<>();
        CountDownLatch chunks3Latch = new CountDownLatch(1);
        ContentResponse contentResponse = client.newRequest(newURI(transport))
            .path("/")
            .onResponseContentSource((response, contentSource) -> accumulateChunks(response, contentSource, chunks1))
            .onResponseContentSource((response, contentSource) -> accumulateChunks(response, contentSource, chunks2))
            .onResponseContentSource((response, contentSource) ->
                new Thread(() ->
                {
                    contentSource.fail(new Exception("Synthetic Failure"));
                    contentSource.demand(() ->
                    {
                        chunks3.add(contentSource.read());
                        chunks3Latch.countDown();
                    });
                }).start())
            .send();
        assertThat(contentResponse.getStatus(), is(200));
        assertThat(contentResponse.getContent().length, is(totalBytes));

        assertThat(chunks1.stream().mapToInt(c -> c.getByteBuffer().remaining()).sum(), is(totalBytes));
        assertThat(chunks2.stream().mapToInt(c -> c.getByteBuffer().remaining()).sum(), is(totalBytes));

        assertThat(chunks3Latch.await(5, TimeUnit.SECONDS), is(true));
        assertThat(chunks3.stream().mapToInt(c -> c.getByteBuffer().remaining()).sum(), is(0));
        assertThat(chunks3.size(), is(1));
        assertThat(chunks3.get(0), instanceOf(Content.Chunk.Error.class));
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testParallelContentSourceListenersTotalFailure(Transport transport) throws Exception
    {
        start(transport, new TestProcessor(1024));

        CompleteContentSourceListener listener = new CompleteContentSourceListener()
        {
            @Override
            public void onContentSource(Response response, Content.Source contentSource)
            {
                contentSource.fail(new Exception("Synthetic Failure"));
            }
        };
        client.newRequest(newURI(transport))
            .path("/")
            .onResponseContentSource((response, contentSource) -> contentSource.fail(new Exception("Synthetic Failure")))
            .onResponseContentSource((response, contentSource) -> contentSource.fail(new Exception("Synthetic Failure")))
            .send(listener);
        assertThat(listener.await(5, TimeUnit.SECONDS), is(true));

        assertThat(listener.result.isFailed(), is(true));
        assertThat(listener.result.getFailure().getMessage(), is("Synthetic Failure"));
    }

    private static void sleep(long time) throws IOException
    {
        try
        {
            Thread.sleep(time);
        }
        catch (InterruptedException x)
        {
            throw new InterruptedIOException();
        }
    }

    private static void accumulateChunks(Response response, Content.Source contentSource, List<Content.Chunk> chunks)
    {
        Content.Chunk chunk = contentSource.read();
        if (chunk == null)
        {
            contentSource.demand(() -> accumulateChunks(response, contentSource, chunks));
            return;
        }

        chunks.add(duplicateAndRelease(chunk));

        if (!chunk.isLast())
            contentSource.demand(() -> accumulateChunks(response, contentSource, chunks));
    }

    private static void accumulateChunksInSpawnedThread(Response response, Content.Source contentSource, List<Content.Chunk> chunks)
    {
        Content.Chunk chunk = contentSource.read();
        if (chunk == null)
        {
            contentSource.demand(() -> new Thread(() -> accumulateChunks(response, contentSource, chunks)).start());
            return;
        }

        chunks.add(duplicateAndRelease(chunk));

        if (!chunk.isLast())
            contentSource.demand(() -> new Thread(() -> accumulateChunks(response, contentSource, chunks)).start());
    }

    private static Content.Chunk duplicateAndRelease(Content.Chunk chunk)
    {
        if (chunk == null || chunk.isTerminal())
            return chunk;

        ByteBuffer buffer = BufferUtil.allocate(chunk.remaining());
        int pos = BufferUtil.flipToFill(buffer);
        buffer.put(chunk.getByteBuffer());
        BufferUtil.flipToFlush(buffer, pos);
        chunk.release();
        return Content.Chunk.from(buffer, chunk.isLast());
    }

    private static class TestProcessor extends Handler.Abstract
    {
        private final int totalBytes;

        private TestProcessor(int totalBytes)
        {
            this.totalBytes = totalBytes;
        }

        @Override
        public void process(Request request, org.eclipse.jetty.server.Response response, Callback callback) throws Exception
        {
            response.getHeaders().put(HttpHeader.CONTENT_TYPE, "text/plain");

            IteratingCallback iteratingCallback = new IteratingNestedCallback(callback)
            {
                int count = 0;
                @Override
                protected Action process()
                {
                    boolean last = ++count == totalBytes;
                    if (count > totalBytes)
                        return Action.SUCCEEDED;
                    response.write(last, ByteBuffer.wrap(new byte[1]), this);
                    return Action.SCHEDULED;
                }
            };
            iteratingCallback.iterate();
        }
    }

    private abstract static class CompleteContentSourceListener implements Response.CompleteListener, Response.ContentSourceListener
    {
        private final CountDownLatch latch = new CountDownLatch(1);
        private Result result;

        @Override
        public void onComplete(Result result)
        {
            this.result = result;
            latch.countDown();
        }

        public boolean await(long timeout, TimeUnit unit) throws InterruptedException
        {
            return latch.await(timeout, unit);
        }
    }
}
