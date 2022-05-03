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

package org.eclipse.jetty.http2.tests;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.client.HttpRequest;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.util.AsyncRequestContent;
import org.eclipse.jetty.client.util.StringRequestContent;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http2.api.Stream;
import org.eclipse.jetty.http2.api.server.ServerSessionListener;
import org.eclipse.jetty.http2.frames.DataFrame;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import org.eclipse.jetty.util.Callback;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class RequestTrailersTest extends AbstractTest
{
    @Test
    public void testEmptyTrailersWithoutContent() throws Exception
    {
        testEmptyTrailers(null);
    }

    @Test
    public void testEmptyTrailersWithEagerContent() throws Exception
    {
        testEmptyTrailers("eager_content");
    }

    private void testEmptyTrailers(String content) throws Exception
    {
        CountDownLatch trailersLatch = new CountDownLatch(1);
        start(new ServerSessionListener.Adapter()
        {
            @Override
            public Stream.Listener onNewStream(Stream stream, HeadersFrame frame)
            {
                MetaData.Response response = new MetaData.Response(HttpVersion.HTTP_2, HttpStatus.OK_200, HttpFields.EMPTY);
                HeadersFrame responseFrame = new HeadersFrame(stream.getId(), response, null, true);
                stream.headers(responseFrame, Callback.NOOP);
                return new Stream.Listener.Adapter()
                {
                    @Override
                    public void onHeaders(Stream stream, HeadersFrame frame)
                    {
                        trailersLatch.countDown();
                    }
                };
            }
        });

        HttpRequest request = (HttpRequest)httpClient.newRequest("localhost", connector.getLocalPort());
        HttpFields.Mutable trailers = HttpFields.build();
        request.trailers(() -> trailers);
        if (content != null)
            request.body(new StringRequestContent(content));

        ContentResponse response = request.send();
        assertEquals(HttpStatus.OK_200, response.getStatus());

        // The client must not send the trailers.
        assertFalse(trailersLatch.await(1, TimeUnit.SECONDS));
    }

    @Test
    public void testEmptyTrailersWithAsyncContent() throws Exception
    {
        start(new ServerSessionListener.Adapter()
        {
            @Override
            public Stream.Listener onNewStream(Stream stream, HeadersFrame frame)
            {
                return new Stream.Listener.Adapter()
                {
                    @Override
                    public void onData(Stream stream, DataFrame dataFrame, Callback callback)
                    {
                        callback.succeeded();
                        // We should not receive an empty HEADERS frame for the
                        // trailers, but instead a DATA frame with endStream=true.
                        if (dataFrame.isEndStream())
                        {
                            MetaData.Response response = new MetaData.Response(HttpVersion.HTTP_2, HttpStatus.OK_200, HttpFields.EMPTY);
                            HeadersFrame responseFrame = new HeadersFrame(stream.getId(), response, null, true);
                            stream.headers(responseFrame, Callback.NOOP);
                        }
                    }
                };
            }
        });

        HttpRequest request = (HttpRequest)httpClient.newRequest("localhost", connector.getLocalPort());
        HttpFields.Mutable trailers = HttpFields.build();
        request.trailers(() -> trailers);
        AsyncRequestContent content = new AsyncRequestContent();
        request.body(content);

        CountDownLatch latch = new CountDownLatch(1);
        request.send(result ->
        {
            assertTrue(result.isSucceeded());
            assertEquals(HttpStatus.OK_200, result.getResponse().getStatus());
            latch.countDown();
        });

        // Send async content after a while.
        Thread.sleep(1000);
        content.offer(ByteBuffer.wrap("async_content".getBytes(StandardCharsets.UTF_8)));
        content.close();

        assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testEmptyTrailersWithEmptyAsyncContent() throws Exception
    {
        start(new ServerSessionListener.Adapter()
        {
            @Override
            public Stream.Listener onNewStream(Stream stream, HeadersFrame frame)
            {
                return new Stream.Listener.Adapter()
                {
                    @Override
                    public void onData(Stream stream, DataFrame dataFrame, Callback callback)
                    {
                        callback.succeeded();
                        // We should not receive an empty HEADERS frame for the
                        // trailers, but instead a DATA frame with endStream=true.
                        if (dataFrame.isEndStream())
                        {
                            MetaData.Response response = new MetaData.Response(HttpVersion.HTTP_2, HttpStatus.OK_200, HttpFields.EMPTY);
                            HeadersFrame responseFrame = new HeadersFrame(stream.getId(), response, null, true);
                            stream.headers(responseFrame, Callback.NOOP);
                        }
                    }
                };
            }
        });

        HttpRequest request = (HttpRequest)httpClient.newRequest("localhost", connector.getLocalPort());
        HttpFields.Mutable trailers = HttpFields.build();
        request.trailers(() -> trailers);
        AsyncRequestContent content = new AsyncRequestContent();
        request.body(content);

        CountDownLatch latch = new CountDownLatch(1);
        request.send(result ->
        {
            assertTrue(result.isSucceeded());
            assertEquals(HttpStatus.OK_200, result.getResponse().getStatus());
            latch.countDown();
        });

        // Send async content after a while.
        Thread.sleep(1000);
        content.close();

        assertTrue(latch.await(5, TimeUnit.SECONDS));
    }
}
