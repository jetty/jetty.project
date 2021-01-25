//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.http2.client.http;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.client.HttpRequest;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.util.DeferredContentProvider;
import org.eclipse.jetty.client.util.StringContentProvider;
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
                MetaData.Response response = new MetaData.Response(HttpVersion.HTTP_2, HttpStatus.OK_200, new HttpFields());
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

        HttpRequest request = (HttpRequest)client.newRequest("localhost", connector.getLocalPort());
        HttpFields trailers = new HttpFields();
        request.trailers(() -> trailers);
        if (content != null)
            request.content(new StringContentProvider(content));

        ContentResponse response = request.send();
        assertEquals(HttpStatus.OK_200, response.getStatus());

        // The client must not send the trailers.
        assertFalse(trailersLatch.await(1, TimeUnit.SECONDS));
    }

    @Test
    public void testEmptyTrailersWithDeferredContent() throws Exception
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
                            MetaData.Response response = new MetaData.Response(HttpVersion.HTTP_2, HttpStatus.OK_200, new HttpFields());
                            HeadersFrame responseFrame = new HeadersFrame(stream.getId(), response, null, true);
                            stream.headers(responseFrame, Callback.NOOP);
                        }
                    }
                };
            }
        });

        HttpRequest request = (HttpRequest)client.newRequest("localhost", connector.getLocalPort());
        HttpFields trailers = new HttpFields();
        request.trailers(() -> trailers);
        DeferredContentProvider content = new DeferredContentProvider();
        request.content(content);

        CountDownLatch latch = new CountDownLatch(1);
        request.send(result ->
        {
            assertTrue(result.isSucceeded());
            assertEquals(HttpStatus.OK_200, result.getResponse().getStatus());
            latch.countDown();
        });

        // Send deferred content after a while.
        Thread.sleep(1000);
        content.offer(ByteBuffer.wrap("deferred_content".getBytes(StandardCharsets.UTF_8)));
        content.close();

        assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testEmptyTrailersWithEmptyDeferredContent() throws Exception
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
                            MetaData.Response response = new MetaData.Response(HttpVersion.HTTP_2, HttpStatus.OK_200, new HttpFields());
                            HeadersFrame responseFrame = new HeadersFrame(stream.getId(), response, null, true);
                            stream.headers(responseFrame, Callback.NOOP);
                        }
                    }
                };
            }
        });

        HttpRequest request = (HttpRequest)client.newRequest("localhost", connector.getLocalPort());
        HttpFields trailers = new HttpFields();
        request.trailers(() -> trailers);
        DeferredContentProvider content = new DeferredContentProvider();
        request.content(content);

        CountDownLatch latch = new CountDownLatch(1);
        request.send(result ->
        {
            assertTrue(result.isSucceeded());
            assertEquals(HttpStatus.OK_200, result.getResponse().getStatus());
            latch.countDown();
        });

        // Send deferred content after a while.
        Thread.sleep(1000);
        content.close();

        assertTrue(latch.await(5, TimeUnit.SECONDS));
    }
}
