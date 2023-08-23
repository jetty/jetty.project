//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.fcgi.server;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPOutputStream;

import org.eclipse.jetty.client.AsyncRequestContent;
import org.eclipse.jetty.client.BytesRequestContent;
import org.eclipse.jetty.client.ConnectionPool;
import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.client.Destination;
import org.eclipse.jetty.client.FutureResponseListener;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.Request;
import org.eclipse.jetty.client.Response;
import org.eclipse.jetty.fcgi.client.transport.HttpClientTransportOverFCGI;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.logging.StacklessLogging;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpChannel;
import org.eclipse.jetty.server.HttpStream;
import org.eclipse.jetty.toolchain.test.Net;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Fields;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

// @checkstyle-disable-check : AvoidEscapedUnicodeCharactersCheck
public class HttpClientTest extends AbstractHttpClientServerTest
{
    @Test
    public void testGETResponseWithoutContent() throws Exception
    {
        start(new Handler.Abstract()
        {
            @Override
            public boolean handle(org.eclipse.jetty.server.Request request, org.eclipse.jetty.server.Response response, Callback callback)
            {
                callback.succeeded();
                return true;
            }
        });

        for (int i = 0; i < 2; ++i)
        {
            Response response = client.GET(scheme + "://localhost:" + connector.getLocalPort());
            assertNotNull(response);
            assertEquals(200, response.getStatus());
        }
    }

    @Test
    public void testGETResponseWithContent() throws Exception
    {
        byte[] data = new byte[]{0, 1, 2, 3, 4, 5, 6, 7};
        start(new Handler.Abstract()
        {
            @Override
            public boolean handle(org.eclipse.jetty.server.Request request, org.eclipse.jetty.server.Response response, Callback callback)
            {
                response.write(true, ByteBuffer.wrap(data), callback);
                return true;
            }
        });

        int maxConnections = 256;
        client.setMaxConnectionsPerDestination(maxConnections);

        for (int i = 0; i < maxConnections + 1; ++i)
        {
            ContentResponse response = client.GET(scheme + "://localhost:" + connector.getLocalPort());
            assertNotNull(response);
            assertEquals(200, response.getStatus());
            byte[] content = response.getContent();
            assertArrayEquals(data, content);
        }
    }

    @Test
    public void testGETResponseWithContentNoContentLength() throws Exception
    {
        byte[] data = new byte[]{0, 1, 2, 3, 4, 5, 6, 7};
        start(new Handler.Abstract()
        {
            @Override
            public boolean handle(org.eclipse.jetty.server.Request request, org.eclipse.jetty.server.Response response, Callback callback)
            {
                request.addHttpStreamWrapper(stream -> new HttpStream.Wrapper(stream)
                {
                    @Override
                    public void send(MetaData.Request req, MetaData.Response rsp, boolean last, ByteBuffer content, Callback cbk)
                    {
                        HttpFields.Mutable rspHeaders = HttpFields.build(rsp.getHttpFields())
                            .remove(HttpHeader.CONTENT_LENGTH);
                        rsp = new MetaData.Response(rsp.getStatus(), rsp.getReason(), rsp.getHttpVersion(), rspHeaders, rsp.getContentLength(), rsp.getTrailersSupplier());
                        super.send(req, rsp, last, content, cbk);
                    }
                });
                response.write(true, ByteBuffer.wrap(data), callback);
                return true;
            }
        });

        ContentResponse response = client.GET(scheme + "://localhost:" + connector.getLocalPort());
        assertNotNull(response);
        assertEquals(200, response.getStatus());
        byte[] content = response.getContent();
        assertArrayEquals(data, content);
    }

    @Test
    public void testGETResponseWithHeadersAndContentInSingleFrame() throws Exception
    {
        // WordPress generates headers and content in a single FrameType.STDOUT frame.
        try (ServerSocketChannel serverChannel = ServerSocketChannel.open())
        {
            serverChannel.bind(new InetSocketAddress(0));

            client = new HttpClient(new HttpClientTransportOverFCGI(1, ""));
            client.start();

            Request request = client.newRequest("localhost", serverChannel.socket().getLocalPort());
            FutureResponseListener listener = new FutureResponseListener(request);
            request.send(listener);

            try (SocketChannel channel = serverChannel.accept())
            {
                channel.read(ByteBuffer.allocate(1024));

                byte[] responseBytes = """
                    Status: 200 OK\r
                    Server: Jetty\r
                    \r
                    hello world
                    """.getBytes(UTF_8);

                ByteBuffer responseByteBuffer = ByteBuffer.allocate(1024)
                    .put((byte)0x01) //FCGI version
                    .put((byte)0x06) // FCGI frame type STDOUT
                    .putShort((short)0x01) // FCGI request id
                    .putShort((short)responseBytes.length) // FCGI content length
                    .put((byte)0) // FCGI padding length
                    .put((byte)0) // FCGI reserved
                    .put(responseBytes)
                    // FCGI end of STDOUT stream.
                    .put(new byte[]{1, 6, 0, 1, 0, 0, 0, 0})
                    // FCGI end request.
                    .put(new byte[]{1, 3, 0, 1, 0, 8, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0})
                    .flip();
                channel.write(responseByteBuffer);

                ContentResponse response = listener.get(5, TimeUnit.SECONDS);
                assertEquals(200, response.getStatus());
                String content = response.getContentAsString();
                assertEquals("hello world\n", content);
            }
        }
    }

    @Test
    public void testGETResponseWithBigContent() throws Exception
    {
        byte[] data = new byte[16 * 1024 * 1024];
        new Random().nextBytes(data);
        start(new Handler.Abstract()
        {
            @Override
            public boolean handle(org.eclipse.jetty.server.Request request, org.eclipse.jetty.server.Response response, Callback callback)
            {
                // Setting the Content-Length triggers the HTTP
                // content mode for response content parsing,
                // otherwise the RAW content mode is used.
                response.getHeaders().put(HttpHeader.CONTENT_LENGTH, data.length);
                response.write(true, ByteBuffer.wrap(data), callback);
                return true;
            }
        });

        Request request = client.newRequest(scheme + "://localhost:" + connector.getLocalPort());
        FutureResponseListener listener = new FutureResponseListener(request, data.length);
        request.send(listener);
        ContentResponse response = listener.get(15, TimeUnit.SECONDS);
        assertNotNull(response);
        assertEquals(200, response.getStatus());
        byte[] content = response.getContent();
        assertArrayEquals(data, content);
    }

    @Test
    public void testGETWithParametersResponseWithContent() throws Exception
    {
        String paramName1 = "a";
        String paramName2 = "b";
        start(new Handler.Abstract()
        {
            @Override
            public boolean handle(org.eclipse.jetty.server.Request request, org.eclipse.jetty.server.Response response, Callback callback) throws Exception
            {
                response.getHeaders().put(HttpHeader.CONTENT_TYPE, "text/plain;charset=utf-8");
                Fields fields = org.eclipse.jetty.server.Request.extractQueryParameters(request);
                String paramValue1 = fields.getValue(paramName1);
                Content.Sink.write(response, false, UTF_8.encode(paramValue1));
                String paramValue2 = fields.getValue(paramName2);
                assertEquals("", paramValue2);
                Content.Sink.write(response, true, UTF_8.encode("empty"));
                callback.succeeded();
                return true;
            }
        });

        String value1 = "\u20AC";
        String paramValue1 = URLEncoder.encode(value1, UTF_8);
        String query = paramName1 + "=" + paramValue1 + "&" + paramName2;
        ContentResponse response = client.GET(scheme + "://localhost:" + connector.getLocalPort() + "/?" + query);

        assertNotNull(response);
        assertEquals(200, response.getStatus());
        String content = new String(response.getContent(), UTF_8);
        assertEquals(value1 + "empty", content);
    }

    @Test
    public void testGETWithParametersMultiValuedResponseWithContent() throws Exception
    {
        String paramName1 = "a";
        String paramName2 = "b";
        start(new Handler.Abstract()
        {
            @Override
            public boolean handle(org.eclipse.jetty.server.Request request, org.eclipse.jetty.server.Response response, Callback callback) throws Exception
            {
                response.getHeaders().put(HttpHeader.CONTENT_TYPE, "text/plain;charset=utf-8");
                Fields fields = org.eclipse.jetty.server.Request.extractQueryParameters(request);
                List<String> paramValues1 = fields.getValues(paramName1);
                for (String paramValue : paramValues1)
                {
                    Content.Sink.write(response, false, UTF_8.encode(paramValue));
                }
                String paramValue2 = fields.getValue(paramName2);
                Content.Sink.write(response, true, UTF_8.encode(paramValue2));
                callback.succeeded();
                return true;
            }
        });

        String value11 = "\u20AC";
        String value12 = "\u20AA";
        String value2 = "&";
        String paramValue11 = URLEncoder.encode(value11, UTF_8);
        String paramValue12 = URLEncoder.encode(value12, UTF_8);
        String paramValue2 = URLEncoder.encode(value2, UTF_8);
        String query = paramName1 + "=" + paramValue11 + "&" + paramName1 + "=" + paramValue12 + "&" + paramName2 + "=" + paramValue2;
        ContentResponse response = client.GET(scheme + "://localhost:" + connector.getLocalPort() + "/?" + query);

        assertNotNull(response);
        assertEquals(200, response.getStatus());
        String content = new String(response.getContent(), UTF_8);
        assertEquals(value11 + value12 + value2, content);
    }

    @Test
    public void testPOSTWithParameters() throws Exception
    {
        String paramName = "a";
        String paramValue = "\u20AC";
        start(new Handler.Abstract()
        {
            @Override
            public boolean handle(org.eclipse.jetty.server.Request request, org.eclipse.jetty.server.Response response, Callback callback)
            {
                Fields fields = org.eclipse.jetty.server.Request.extractQueryParameters(request);
                String value = fields.getValue(paramName);
                if (paramValue.equals(value))
                {
                    response.getHeaders().put(HttpHeader.CONTENT_TYPE, "text/plain;charset=utf-8");
                    Content.Sink.write(response, true, value, callback);
                }
                return true;
            }
        });

        ContentResponse response = client.POST(scheme + "://localhost:" + connector.getLocalPort())
            .param(paramName, paramValue)
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertNotNull(response);
        assertEquals(200, response.getStatus());
        assertEquals(paramValue, new String(response.getContent(), UTF_8));
    }

    @Test
    public void testPOSTWithQueryString() throws Exception
    {
        String paramName = "a";
        String paramValue = "\u20AC";
        start(new Handler.Abstract()
        {
            @Override
            public boolean handle(org.eclipse.jetty.server.Request request, org.eclipse.jetty.server.Response response, Callback callback)
            {
                Fields fields = org.eclipse.jetty.server.Request.extractQueryParameters(request);
                String value = fields.getValue(paramName);
                if (paramValue.equals(value))
                {
                    response.getHeaders().put(HttpHeader.CONTENT_TYPE, "text/plain;charset=utf-8");
                    Content.Sink.write(response, true, value, callback);
                }
                return true;
            }
        });

        String uri = scheme + "://localhost:" + connector.getLocalPort() +
            "/?" + paramName + "=" + URLEncoder.encode(paramValue, UTF_8);
        ContentResponse response = client.POST(uri)
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertNotNull(response);
        assertEquals(200, response.getStatus());
        assertEquals(paramValue, new String(response.getContent(), UTF_8));
    }

    @Test
    public void testPUTWithParameters() throws Exception
    {
        String paramName = "a";
        String paramValue = "\u20AC";
        start(new Handler.Abstract()
        {
            @Override
            public boolean handle(org.eclipse.jetty.server.Request request, org.eclipse.jetty.server.Response response, Callback callback)
            {
                Fields fields = org.eclipse.jetty.server.Request.extractQueryParameters(request);
                String value = fields.getValue(paramName);
                if (paramValue.equals(value))
                {
                    response.getHeaders().put(HttpHeader.CONTENT_TYPE, "text/plain;charset=utf-8");
                    Content.Sink.write(response, true, value, callback);
                }
                return true;
            }
        });

        URI uri = URI.create(scheme + "://localhost:" + connector.getLocalPort() + "/path?" + paramName + "=" + paramValue);
        ContentResponse response = client.newRequest(uri)
            .method(HttpMethod.PUT)
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertNotNull(response);
        assertEquals(200, response.getStatus());
        assertEquals(paramValue, new String(response.getContent(), UTF_8));
    }

    @Test
    public void testPOSTWithParametersWithContent() throws Exception
    {
        byte[] content = {0, 1, 2, 3};
        String paramName = "a";
        String paramValue = "\u20AC";
        start(new Handler.Abstract()
        {
            @Override
            public boolean handle(org.eclipse.jetty.server.Request request, org.eclipse.jetty.server.Response response, Callback callback)
            {
                Fields fields = org.eclipse.jetty.server.Request.extractQueryParameters(request);
                String value = fields.getValue(paramName);
                if (paramValue.equals(value))
                {
                    response.getHeaders().put(HttpHeader.CONTENT_TYPE, "application/octet-stream");
                    Content.copy(request, response, callback);
                }
                return true;
            }
        });

        for (int i = 0; i < 256; ++i)
        {
            ContentResponse response = client.POST(scheme + "://localhost:" + connector.getLocalPort() + "/?r=" + i)
                .param(paramName, paramValue)
                .body(new BytesRequestContent(content))
                .timeout(5, TimeUnit.SECONDS)
                .send();

            assertNotNull(response);
            assertEquals(200, response.getStatus());
            assertArrayEquals(content, response.getContent(), "content mismatch for request " + i);
        }
    }

    @Test
    public void testPOSTWithContentNotifiesRequestContentListener() throws Exception
    {
        byte[] content = {0, 1, 2, 3};
        start(new Handler.Abstract()
        {
            @Override
            public boolean handle(org.eclipse.jetty.server.Request request, org.eclipse.jetty.server.Response response, Callback callback)
            {
                callback.succeeded();
                return true;
            }
        });

        ContentResponse response = client.POST(scheme + "://localhost:" + connector.getLocalPort())
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

    @Test
    public void testPOSTWithContentTracksProgress() throws Exception
    {
        start(new Handler.Abstract()
        {
            @Override
            public boolean handle(org.eclipse.jetty.server.Request request, org.eclipse.jetty.server.Response response, Callback callback)
            {
                Content.Source.consumeAll(request, callback);
                return true;
            }
        });

        AtomicInteger progress = new AtomicInteger();
        ContentResponse response = client.POST(scheme + "://localhost:" + connector.getLocalPort())
            .onRequestContent((request, buffer) ->
            {
                byte[] bytes = new byte[buffer.remaining()];
                assertEquals(1, bytes.length);
                buffer.get(bytes);
                assertEquals(bytes[0], progress.getAndIncrement());
            })
            .body(new BytesRequestContent(new byte[]{0}, new byte[]{1}, new byte[]{
                2
            }, new byte[]{3}, new byte[]{4}))
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertNotNull(response);
        assertEquals(200, response.getStatus());
        assertEquals(5, progress.get());
    }

    @Test
    public void testGZIPContentEncoding() throws Exception
    {
        byte[] data = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9};
        start(new Handler.Abstract()
        {
            @Override
            public boolean handle(org.eclipse.jetty.server.Request request, org.eclipse.jetty.server.Response response, Callback callback) throws Exception
            {
                response.getHeaders().put("Content-Encoding", "gzip");
                OutputStream outputStream = Content.Sink.asOutputStream(response);
                GZIPOutputStream gzipOutput = new GZIPOutputStream(outputStream);
                gzipOutput.write(data);
                gzipOutput.finish();
                callback.succeeded();
                return true;
            }
        });

        ContentResponse response = client.newRequest("localhost", connector.getLocalPort())
            .scheme(scheme)
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertEquals(200, response.getStatus());
        assertArrayEquals(data, response.getContent());
    }

    @Test
    public void testConnectionIdleTimeout() throws Exception
    {
        long idleTimeout = 1000;
        start(new Handler.Abstract()
        {
            @Override
            public boolean handle(org.eclipse.jetty.server.Request request, org.eclipse.jetty.server.Response response, Callback callback)
            {
                callback.succeeded();
                return true;
            }
        });
        connector.setIdleTimeout(idleTimeout);

        ContentResponse response = client.newRequest("localhost", connector.getLocalPort())
            .scheme(scheme)
            .timeout(2 * idleTimeout, TimeUnit.MILLISECONDS)
            .send();
        assertNotNull(response);
        assertEquals(200, response.getStatus());

        Thread.sleep(2 * idleTimeout);

        assertTrue(client.getDestinations().stream()
            .map(Destination::getConnectionPool)
            .allMatch(ConnectionPool::isEmpty));
    }

    @Test
    public void testConnectionIdleTimeoutIgnored() throws Exception
    {
        long idleTimeout = 1000;
        start(new Handler.Abstract()
        {
            @Override
            public boolean handle(org.eclipse.jetty.server.Request request, org.eclipse.jetty.server.Response response, Callback callback) throws Exception
            {
                // Handler says it will handle the idle timeout by ignoring it.
                request.addIdleTimeoutListener(t -> false);
                // Sleep an non-integral number of idle timeouts to avoid
                // racing with the idle timeout ticking every idle period.
                TimeUnit.MILLISECONDS.sleep(idleTimeout * 3 / 2);
                callback.succeeded();
                return true;
            }
        });

        connector.setIdleTimeout(idleTimeout);

        // Request does not fail because handler says it will handle it.
        ContentResponse response1 = client.newRequest("localhost", connector.getLocalPort())
            .scheme(scheme)
            .idleTimeout(4 * idleTimeout, TimeUnit.MILLISECONDS)
            .timeout(3 * idleTimeout, TimeUnit.MILLISECONDS)
            .send();
        assertNotNull(response1);
        assertEquals(200, response1.getStatus());

        connector.setIdleTimeout(5 * idleTimeout);

        // Make another request to be sure the connection works fine.
        ContentResponse response2 = client.newRequest("localhost", connector.getLocalPort())
            .scheme(scheme)
            .idleTimeout(4 * idleTimeout, TimeUnit.MILLISECONDS)
            .timeout(3 * idleTimeout, TimeUnit.MILLISECONDS)
            .send();

        assertNotNull(response2);
        assertEquals(200, response2.getStatus());
    }

    @Test
    public void testSendToIPv6Address() throws Exception
    {
        Assumptions.assumeTrue(Net.isIpv6InterfaceAvailable());
        start(new Handler.Abstract()
        {
            @Override
            public boolean handle(org.eclipse.jetty.server.Request request, org.eclipse.jetty.server.Response response, Callback callback)
            {
                callback.succeeded();
                return true;
            }
        });

        ContentResponse response = client.newRequest("[::1]", connector.getLocalPort())
            .scheme(scheme)
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertNotNull(response);
        assertEquals(200, response.getStatus());
    }

    @Test
    public void testHEADWithResponseContentLength() throws Exception
    {
        int length = 1024;
        start(new Handler.Abstract()
        {
            @Override
            public boolean handle(org.eclipse.jetty.server.Request request, org.eclipse.jetty.server.Response response, Callback callback)
            {
                response.write(true, ByteBuffer.wrap(new byte[length]), callback);
                return true;
            }
        });

        // HEAD requests receive a Content-Length header, but do not
        // receive the content so they must handle this case properly
        ContentResponse response = client.newRequest("localhost", connector.getLocalPort())
            .scheme(scheme)
            .method(HttpMethod.HEAD)
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertNotNull(response);
        assertEquals(200, response.getStatus());
        assertEquals(0, response.getContent().length);

        // Perform a normal GET request to be sure the content is now read
        response = client.newRequest("localhost", connector.getLocalPort())
            .scheme(scheme)
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertNotNull(response);
        assertEquals(200, response.getStatus());
        assertEquals(length, response.getContent().length);
    }

    @Test
    public void testLongPollIsAbortedWhenClientIsStopped() throws Exception
    {
        AtomicReference<Callback> callbackRef = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        start(new Handler.Abstract()
        {
            @Override
            public boolean handle(org.eclipse.jetty.server.Request request, org.eclipse.jetty.server.Response response, Callback callback)
            {
                latch.countDown();
                // Do not complete the callback, but store it aside for
                // releasing the buffer later on.
                callbackRef.set(callback);
                return true;
            }
        });

        try
        {
            CountDownLatch completeLatch = new CountDownLatch(1);
            client.newRequest("localhost", connector.getLocalPort())
                .scheme(scheme)
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
        finally
        {
            // Release the buffer.
            Callback callback = callbackRef.get();
            if (callback != null)
                callback.succeeded();
        }
    }

    @Test
    public void testEarlyEOF() throws Exception
    {
        start(new Handler.Abstract()
        {
            @Override
            public boolean handle(org.eclipse.jetty.server.Request request, org.eclipse.jetty.server.Response response, Callback callback) throws Exception
            {
                // Promise some content, then flush the headers, then fail to send the content.
                response.getHeaders().put(HttpHeader.CONTENT_LENGTH, 16);
                Content.Sink.write(response, false, null);
                throw new NullPointerException("Explicitly thrown by test");
            }
        });

        try (StacklessLogging ignore = new StacklessLogging(HttpChannel.class))
        {
            assertThrows(ExecutionException.class, () ->
                client.newRequest("localhost", connector.getLocalPort())
                    .scheme(scheme)
                    .timeout(60, TimeUnit.SECONDS)
                    .send());
        }
    }

    @Test
    public void testSmallContentDelimitedByEOFWithSlowRequest() throws Exception
    {
        testContentDelimitedByEOFWithSlowRequest(1024);
    }

    @Test
    public void testBigContentDelimitedByEOFWithSlowRequest() throws Exception
    {
        testContentDelimitedByEOFWithSlowRequest(128 * 1024);
    }

    private void testContentDelimitedByEOFWithSlowRequest(int length) throws Exception
    {
        byte[] data = new byte[length];
        new Random().nextBytes(data);
        start(new Handler.Abstract()
        {
            @Override
            public boolean handle(org.eclipse.jetty.server.Request request, org.eclipse.jetty.server.Response response, Callback callback)
            {
                response.getHeaders().put("Connection", "close");
                response.write(true, ByteBuffer.wrap(data), callback);
                return true;
            }
        });

        AsyncRequestContent content = new AsyncRequestContent(ByteBuffer.wrap(new byte[]{0}));
        Request request = client.newRequest("localhost", connector.getLocalPort())
            .scheme(scheme)
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

    @Test
    public void testSmallAsyncContent() throws Exception
    {
        start(new Handler.Abstract()
        {
            @Override
            public boolean handle(org.eclipse.jetty.server.Request request, org.eclipse.jetty.server.Response response, Callback callback) throws Exception
            {
                Content.Sink.write(response, false, UTF_8.encode("A"));
                Content.Sink.write(response, true, UTF_8.encode("B"));
                callback.succeeded();
                return true;
            }
        });

        AtomicInteger contentCount = new AtomicInteger();
        AtomicReference<Runnable> demanderRef = new AtomicReference<>();
        AtomicReference<CountDownLatch> contentLatch = new AtomicReference<>(new CountDownLatch(1));
        CountDownLatch completeLatch = new CountDownLatch(1);
        client.newRequest("localhost", connector.getLocalPort())
            .scheme(scheme)
            .onResponseContentAsync((response, chunk, demander) ->
            {
                contentCount.incrementAndGet();
                demanderRef.set(demander);
                contentLatch.get().countDown();
            })
            .send(result -> completeLatch.countDown());

        assertTrue(contentLatch.get().await(5, TimeUnit.SECONDS));
        Runnable demander = demanderRef.get();

        // Wait a while to be sure that the parsing does not proceed.
        TimeUnit.MILLISECONDS.sleep(1000);

        assertEquals(1, contentCount.get());

        // Succeed the content callback to proceed with parsing.
        demanderRef.set(null);
        contentLatch.set(new CountDownLatch(1));
        demander.run();

        assertTrue(contentLatch.get().await(5, TimeUnit.SECONDS));
        demander = demanderRef.get();

        // Wait a while to be sure that the parsing does not proceed.
        TimeUnit.MILLISECONDS.sleep(1000);

        assertEquals(2, contentCount.get());
        assertEquals(1, completeLatch.getCount());

        // Succeed the content callback to proceed with parsing.
        demanderRef.set(null);
        contentLatch.set(new CountDownLatch(1));
        demander.run();

        assertTrue(completeLatch.await(5, TimeUnit.SECONDS));
        assertEquals(2, contentCount.get());
    }
}
