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

package org.eclipse.jetty.http3.tests;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.HttpClientTransport;
import org.eclipse.jetty.client.Response;
import org.eclipse.jetty.client.transport.HttpClientTransportDynamic;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpScheme;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http3.HTTP3Configuration;
import org.eclipse.jetty.http3.client.HTTP3Client;
import org.eclipse.jetty.http3.client.transport.ClientConnectionFactoryOverHTTP3;
import org.eclipse.jetty.http3.client.transport.HttpClientTransportOverHTTP3;
import org.eclipse.jetty.io.ClientConnector;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.quic.client.ClientQuicConfiguration;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class HttpClientTransportOverHTTP3Test extends AbstractClientServerTest
{
    @Test
    public void testPropertiesAreForwardedOverHTTP3() throws Exception
    {
        ClientConnector clientConnector = new ClientConnector();
        HTTP3Client http3Client = new HTTP3Client(new ClientQuicConfiguration(new SslContextFactory.Client(), null), clientConnector);
        testPropertiesAreForwarded(http3Client, new HttpClientTransportOverHTTP3(http3Client));
    }

    @Test
    public void testPropertiesAreForwardedDynamic() throws Exception
    {
        ClientConnector clientConnector = new ClientConnector();
        HTTP3Client http3Client = new HTTP3Client(new ClientQuicConfiguration(new SslContextFactory.Client(), null), clientConnector);
        testPropertiesAreForwarded(http3Client, new HttpClientTransportDynamic(clientConnector, new ClientConnectionFactoryOverHTTP3.HTTP3(http3Client)));
    }

    private void testPropertiesAreForwarded(HTTP3Client http3Client, HttpClientTransport httpClientTransport) throws Exception
    {
        try (HttpClient httpClient = new HttpClient(httpClientTransport))
        {
            Executor executor = new QueuedThreadPool();
            httpClient.setExecutor(executor);
            httpClient.setConnectTimeout(13);
            httpClient.setIdleTimeout(17);
            httpClient.setUseInputDirectByteBuffers(false);
            httpClient.setUseOutputDirectByteBuffers(false);

            httpClient.start();

            assertTrue(http3Client.isStarted());
            ClientConnector clientConnector = http3Client.getClientConnector();
            assertSame(httpClient.getExecutor(), clientConnector.getExecutor());
            assertSame(httpClient.getScheduler(), clientConnector.getScheduler());
            assertSame(httpClient.getByteBufferPool(), clientConnector.getByteBufferPool());
            assertEquals(httpClient.getConnectTimeout(), clientConnector.getConnectTimeout().toMillis());
            assertEquals(httpClient.getIdleTimeout(), clientConnector.getIdleTimeout().toMillis());
            HTTP3Configuration http3Configuration = http3Client.getHTTP3Configuration();
            assertEquals(httpClient.isUseInputDirectByteBuffers(), http3Configuration.isUseInputDirectByteBuffers());
            assertEquals(httpClient.isUseOutputDirectByteBuffers(), http3Configuration.isUseOutputDirectByteBuffers());
            assertEquals(httpClient.getMaxRequestHeadersSize(), http3Configuration.getMaxRequestHeadersSize());
            assertEquals(httpClient.getMaxResponseHeadersSize(), http3Configuration.getMaxResponseHeadersSize());
        }
        assertTrue(http3Client.isStopped());
    }

    @Test
    public void testRequestHasHTTP3Version() throws Exception
    {
        start(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, org.eclipse.jetty.server.Response response, Callback callback)
            {
                HttpVersion version = HttpVersion.fromString(request.getConnectionMetaData().getProtocol());
                response.setStatus(version == HttpVersion.HTTP_3 ? HttpStatus.OK_200 : HttpStatus.INTERNAL_SERVER_ERROR_500);
                callback.succeeded();
                return true;
            }
        });

        ContentResponse response = httpClient.newRequest("localhost", connector.getLocalPort())
            .scheme(HttpScheme.HTTPS.asString())
            .onRequestBegin(request ->
            {
                if (request.getVersion() != HttpVersion.HTTP_3)
                    request.abort(new Exception("Not an HTTP/3 request"));
            })
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertEquals(HttpStatus.OK_200, response.getStatus());
    }

    @Test
    public void testRequestResponseWithSmallContent() throws Exception
    {
        String content = "Hello, World!";
        start(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, org.eclipse.jetty.server.Response response, Callback callback)
            {
                Content.Sink.write(response, true, content, callback);
                return true;
            }
        });

        ContentResponse response = httpClient.newRequest("https://localhost:" + connector.getLocalPort())
            .timeout(10, TimeUnit.SECONDS)
            .send();
        assertEquals(content, response.getContentAsString());
    }

    @Test
    public void testDelayedClientRead() throws Exception
    {
        start(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, org.eclipse.jetty.server.Response response, Callback callback)
            {
                response.write(true, ByteBuffer.wrap(new byte[10 * 1024]), callback);
                return true;
            }
        });

        AtomicReference<Runnable> demanderRef = new AtomicReference<>();
        CountDownLatch beforeContentLatch = new CountDownLatch(1);
        AtomicInteger contentCount = new AtomicInteger();
        CountDownLatch latch = new CountDownLatch(1);
        httpClient.newRequest("https://localhost:" + connector.getLocalPort())
            .onResponseContentSource(new Response.ContentSourceListener()
            {
                @Override
                public void onContentSource(Response response, Content.Source contentSource)
                {
                    Runnable demander = () -> contentSource.demand(() -> onContentSource(response, contentSource));
                    if (demanderRef.getAndSet(demander) == null)
                    {
                        // 1st time, do not demand.
                        beforeContentLatch.countDown();
                        return;
                    }

                    Content.Chunk chunk = contentSource.read();
                    if (chunk == null)
                    {
                        demander.run();
                        return;
                    }
                    if (chunk.hasRemaining())
                        contentCount.incrementAndGet();
                    chunk.release();
                    if (!chunk.isLast())
                        demander.run();
                }
            })
            .timeout(5, TimeUnit.SECONDS)
            .send(result ->
            {
                assertTrue(result.isSucceeded());
                assertEquals(HttpStatus.OK_200, result.getResponse().getStatus());
                latch.countDown();
            });

        assertTrue(beforeContentLatch.await(5, TimeUnit.SECONDS));

        // Verify that onContent() is not called.
        Thread.sleep(1000);
        assertEquals(0, contentCount.get());

        // Demand content.
        demanderRef.get().run();

        assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testDelayDemandAfterHeaders() throws Exception
    {
        start(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, org.eclipse.jetty.server.Response response, Callback callback)
            {
                callback.succeeded();
                return true;
            }
        });

        AtomicReference<Content.Source> contentSourceRef = new AtomicReference<>();
        CountDownLatch beforeContentLatch = new CountDownLatch(1);
        AtomicInteger contentCount = new AtomicInteger();
        CountDownLatch latch = new CountDownLatch(1);
        httpClient.newRequest("localhost", connector.getLocalPort())
            .scheme(HttpScheme.HTTPS.asString())
            .onResponseContentSource((response, contentSource) ->
            {
                // Do not demand.
                if (contentSourceRef.getAndSet(contentSource) != null)
                    contentCount.incrementAndGet();
                beforeContentLatch.countDown();
            })
            .timeout(5, TimeUnit.SECONDS)
            .send(result ->
            {
                assertTrue(result.isSucceeded());
                assertEquals(HttpStatus.OK_200, result.getResponse().getStatus());
                latch.countDown();
            });

        assertTrue(beforeContentLatch.await(10, TimeUnit.SECONDS));

        // Verify that the response is not completed yet.
        assertFalse(latch.await(1, TimeUnit.SECONDS));

        // Demand to succeed the response.
        contentSourceRef.get().demand(() -> Content.Source.consumeAll(contentSourceRef.get(), Callback.NOOP));

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertEquals(0, contentCount.get());
    }

    @Test
    public void testDelayDemandAfterLastContentChunk() throws Exception
    {
        start(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, org.eclipse.jetty.server.Response response, Callback callback)
            {
                Content.Sink.write(response, true, "0", callback);
                return true;
            }
        });

        AtomicReference<Content.Source> contentSourceRef = new AtomicReference<>();
        CountDownLatch contentLatch = new CountDownLatch(1);
        CountDownLatch latch = new CountDownLatch(1);
        httpClient.newRequest("localhost", connector.getLocalPort())
            .scheme(HttpScheme.HTTPS.asString())
            .onResponseContentSource((response, contentSource) ->
            {
                // Do not demand.
                contentSourceRef.getAndSet(contentSource);
                contentLatch.countDown();
            })
            .timeout(5, TimeUnit.SECONDS)
            .send(result ->
            {
                assertTrue(result.isSucceeded());
                assertEquals(HttpStatus.OK_200, result.getResponse().getStatus());
                latch.countDown();
            });

        assertTrue(contentLatch.await(5, TimeUnit.SECONDS));

        // Verify that the response is not completed yet.
        assertFalse(latch.await(1, TimeUnit.SECONDS));

        // Demand to succeed the response.
        contentSourceRef.get().demand(() -> Content.Source.consumeAll(contentSourceRef.get(), Callback.NOOP));

        assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testDynamicTableReference() throws Exception
    {
        start(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, org.eclipse.jetty.server.Response response, Callback callback)
            {
                HttpFields.Mutable headers = response.getHeaders();
                headers.add("header1", "value1");
                headers.add("header2", "value2");
                headers.add("header3", "value3");
                headers.add("header4", "value4");
                headers.add("header5", "value5");

                // This header should reference the named header already in the dynamic table.
                headers.add("header5", "value6");
                response.write(true, null, callback);
                return true;
            }
        });

        ContentResponse response = httpClient.newRequest("localhost", connector.getLocalPort())
            .scheme(HttpScheme.HTTPS.asString())
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertThat(response.getStatus(), equalTo(HttpStatus.OK_200));
        assertHeader(response, "header1", "value1");
        assertHeader(response, "header2", "value2");
        assertHeader(response, "header3", "value3");
        assertHeader(response, "header4", "value4");
        assertHeader(response, "header5", "value5", "value6");
    }

    private void assertHeader(ContentResponse response, String header, String... values)
    {
        assertThat(response.getHeaders().getValuesList(header), equalTo(Arrays.asList(values)));
    }
}
