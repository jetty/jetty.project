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

package org.eclipse.jetty.http3.tests;

import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.util.Callback;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class HttpClientTransportOverHTTP3Test extends AbstractClientServerTest
{
    @Test
    public void testRequestHasHTTP3Version() throws Exception
    {
        start(new Handler.Abstract()
        {
            @Override
            public boolean process(Request request, org.eclipse.jetty.server.Response response, Callback callback)
            {
                HttpVersion version = HttpVersion.fromString(request.getConnectionMetaData().getProtocol());
                response.setStatus(version == HttpVersion.HTTP_3 ? HttpStatus.OK_200 : HttpStatus.INTERNAL_SERVER_ERROR_500);
                callback.succeeded();
                return true;
            }
        });

        ContentResponse response = httpClient.newRequest("localhost", connector.getLocalPort())
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
            public boolean process(Request request, org.eclipse.jetty.server.Response response, Callback callback)
            {
                Content.Sink.write(response, true, content, callback);
                return true;
            }
        });

        ContentResponse response = httpClient.newRequest("https://localhost:" + connector.getLocalPort())
            .timeout(5, TimeUnit.SECONDS)
            .send();
        assertEquals(content, response.getContentAsString());
    }

    @Test
    public void testDelayedClientRead() throws Exception
    {
        start(new Handler.Abstract()
        {
            @Override
            public boolean process(Request request, org.eclipse.jetty.server.Response response, Callback callback)
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
            public boolean process(Request request, org.eclipse.jetty.server.Response response, Callback callback)
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

        assertTrue(beforeContentLatch.await(5, TimeUnit.SECONDS));

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
            public boolean process(Request request, org.eclipse.jetty.server.Response response, Callback callback)
            {
                Content.Sink.write(response, true, "0", callback);
                return true;
            }
        });

        AtomicReference<Content.Source> contentSourceRef = new AtomicReference<>();
        CountDownLatch contentLatch = new CountDownLatch(1);
        CountDownLatch latch = new CountDownLatch(1);
        httpClient.newRequest("localhost", connector.getLocalPort())
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
}
