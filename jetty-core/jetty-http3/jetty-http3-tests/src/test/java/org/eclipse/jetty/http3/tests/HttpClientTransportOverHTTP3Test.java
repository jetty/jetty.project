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
import java.util.function.LongConsumer;

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
        start(new Handler.Processor()
        {
            @Override
            public void process(Request request, org.eclipse.jetty.server.Response response, Callback callback)
            {
                HttpVersion version = HttpVersion.fromString(request.getConnectionMetaData().getProtocol());
                response.setStatus(version == HttpVersion.HTTP_3 ? HttpStatus.OK_200 : HttpStatus.INTERNAL_SERVER_ERROR_500);
                callback.succeeded();
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
        start(new Handler.Processor()
        {
            @Override
            public void process(Request request, org.eclipse.jetty.server.Response response, Callback callback)
            {
                Content.Sink.write(response, true, content, callback);
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
        start(new Handler.Processor()
        {
            @Override
            public void process(Request request, org.eclipse.jetty.server.Response response, Callback callback)
            {
                response.write(true, ByteBuffer.wrap(new byte[10 * 1024]), callback);
            }
        });

        AtomicReference<LongConsumer> demandRef = new AtomicReference<>();
        CountDownLatch beforeContentLatch = new CountDownLatch(1);
        AtomicInteger contentCount = new AtomicInteger();
        CountDownLatch latch = new CountDownLatch(1);
        httpClient.newRequest("https://localhost:" + connector.getLocalPort())
            .onResponseContentDemanded(new Response.DemandedContentListener()
            {
                @Override
                public void onBeforeContent(Response response, LongConsumer demand)
                {
                    // Do not demand.
                    demandRef.set(demand);
                    beforeContentLatch.countDown();
                }

                @Override
                public void onContent(Response response, LongConsumer demand, ByteBuffer content, Callback callback)
                {
                    contentCount.incrementAndGet();
                    callback.succeeded();
                    demand.accept(1);
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
        demandRef.get().accept(1);

        assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testDelayDemandAfterHeaders() throws Exception
    {
        start(new Handler.Processor()
        {
            @Override
            public void process(Request request, org.eclipse.jetty.server.Response response, Callback callback)
            {
                callback.succeeded();
            }
        });

        AtomicReference<LongConsumer> demandRef = new AtomicReference<>();
        CountDownLatch beforeContentLatch = new CountDownLatch(1);
        AtomicInteger contentCount = new AtomicInteger();
        CountDownLatch latch = new CountDownLatch(1);
        httpClient.newRequest("localhost", connector.getLocalPort())
            .onResponseContentDemanded(new Response.DemandedContentListener()
            {
                @Override
                public void onBeforeContent(Response response, LongConsumer demand)
                {
                    // Do not demand.
                    demandRef.set(demand);
                    beforeContentLatch.countDown();
                }

                @Override
                public void onContent(Response response, LongConsumer demand, ByteBuffer content, Callback callback)
                {
                    contentCount.incrementAndGet();
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

        // Verify that the response is not completed yet.
        assertFalse(latch.await(1, TimeUnit.SECONDS));

        // Demand to succeed the response.
        demandRef.get().accept(1);

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertEquals(0, contentCount.get());
    }

    @Test
    public void testDelayDemandAfterLastContentChunk() throws Exception
    {
        start(new Handler.Processor()
        {
            @Override
            public void process(Request request, org.eclipse.jetty.server.Response response, Callback callback)
            {
                Content.Sink.write(response, true, "0", callback);
            }
        });

        AtomicReference<LongConsumer> demandRef = new AtomicReference<>();
        CountDownLatch contentLatch = new CountDownLatch(1);
        CountDownLatch latch = new CountDownLatch(1);
        httpClient.newRequest("localhost", connector.getLocalPort())
            .onResponseContentDemanded((response, demand, content, callback) ->
            {
                callback.succeeded();
                // Do not demand.
                demandRef.set(demand);
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
        demandRef.get().accept(1);

        assertTrue(latch.await(5, TimeUnit.SECONDS));
    }
}
