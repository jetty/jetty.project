//
// ========================================================================
// Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.http2.client;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http2.api.Session;
import org.eclipse.jetty.http2.api.Stream;
import org.eclipse.jetty.http2.api.server.ServerSessionListener;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import org.eclipse.jetty.http2.frames.PriorityFrame;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.FuturePromise;
import org.eclipse.jetty.util.Promise;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class PriorityTest extends AbstractTest
{
    @Test
    public void testPriorityBeforeHeaders() throws Exception
    {
        start(new ServerSessionListener.Adapter()
        {
            @Override
            public Stream.Listener onNewStream(Stream stream, HeadersFrame frame)
            {
                MetaData.Response metaData = new MetaData.Response(HttpVersion.HTTP_2, 200, HttpFields.EMPTY);
                HeadersFrame responseFrame = new HeadersFrame(stream.getId(), metaData, null, true);
                stream.headers(responseFrame, Callback.NOOP);
                return null;
            }
        });

        Session session = newClient(new Session.Listener.Adapter());
        int streamId = session.priority(new PriorityFrame(0, 13, false), Callback.NOOP);
        assertTrue(streamId > 0);

        CountDownLatch latch = new CountDownLatch(2);
        MetaData metaData = newRequest("GET", HttpFields.EMPTY);
        HeadersFrame headersFrame = new HeadersFrame(streamId, metaData, null, true);
        session.newStream(headersFrame, new Promise.Adapter<Stream>()
        {
            @Override
            public void succeeded(Stream result)
            {
                assertEquals(streamId, result.getId());
                latch.countDown();
            }
        }, new Stream.Listener.Adapter()
        {
            @Override
            public void onHeaders(Stream stream, HeadersFrame frame)
            {
                if (frame.isEndStream())
                    latch.countDown();
            }
        });

        assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testPriorityAfterHeaders() throws Exception
    {
        CountDownLatch beforeRequests = new CountDownLatch(1);
        CountDownLatch afterRequests = new CountDownLatch(2);
        start(new ServerSessionListener.Adapter()
        {
            @Override
            public Stream.Listener onNewStream(Stream stream, HeadersFrame frame)
            {
                try
                {
                    beforeRequests.await(5, TimeUnit.SECONDS);
                    MetaData.Response metaData = new MetaData.Response(HttpVersion.HTTP_2, 200, HttpFields.EMPTY);
                    HeadersFrame responseFrame = new HeadersFrame(stream.getId(), metaData, null, true);
                    stream.headers(responseFrame, Callback.NOOP);
                    afterRequests.countDown();
                    return null;
                }
                catch (InterruptedException x)
                {
                    x.printStackTrace();
                    return null;
                }
            }
        });

        CountDownLatch responses = new CountDownLatch(2);
        Stream.Listener.Adapter listener = new Stream.Listener.Adapter()
        {
            @Override
            public void onHeaders(Stream stream, HeadersFrame frame)
            {
                if (frame.isEndStream())
                    responses.countDown();
            }
        };

        Session session = newClient(new Session.Listener.Adapter());
        MetaData metaData1 = newRequest("GET", "/one", HttpFields.EMPTY);
        HeadersFrame headersFrame1 = new HeadersFrame(metaData1, null, true);
        FuturePromise<Stream> promise1 = new FuturePromise<>();
        session.newStream(headersFrame1, promise1, listener);
        Stream stream1 = promise1.get(5, TimeUnit.SECONDS);

        MetaData metaData2 = newRequest("GET", "/two", HttpFields.EMPTY);
        HeadersFrame headersFrame2 = new HeadersFrame(metaData2, null, true);
        FuturePromise<Stream> promise2 = new FuturePromise<>();
        session.newStream(headersFrame2, promise2, listener);
        Stream stream2 = promise2.get(5, TimeUnit.SECONDS);

        int streamId = session.priority(new PriorityFrame(stream1.getId(), stream2.getId(), 13, false), Callback.NOOP);
        assertEquals(stream1.getId(), streamId);

        // Give time to the PRIORITY frame to arrive to server.
        Thread.sleep(1000);
        beforeRequests.countDown();

        assertTrue(afterRequests.await(5, TimeUnit.SECONDS));
        assertTrue(responses.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testHeadersWithPriority() throws Exception
    {
        PriorityFrame priorityFrame = new PriorityFrame(13, 200, true);
        CountDownLatch latch = new CountDownLatch(2);
        start(new ServerSessionListener.Adapter()
        {
            @Override
            public Stream.Listener onNewStream(Stream stream, HeadersFrame frame)
            {
                PriorityFrame priority = frame.getPriority();
                assertNotNull(priority);
                assertEquals(priorityFrame.getParentStreamId(), priority.getParentStreamId());
                assertEquals(priorityFrame.getWeight(), priority.getWeight());
                assertEquals(priorityFrame.isExclusive(), priority.isExclusive());
                latch.countDown();

                MetaData.Response metaData = new MetaData.Response(HttpVersion.HTTP_2, 200, HttpFields.EMPTY);
                HeadersFrame responseFrame = new HeadersFrame(stream.getId(), metaData, null, true);
                stream.headers(responseFrame, Callback.NOOP);
                return null;
            }
        });

        Session session = newClient(new Session.Listener.Adapter());
        MetaData metaData = newRequest("GET", "/one", HttpFields.EMPTY);
        HeadersFrame headersFrame = new HeadersFrame(metaData, priorityFrame, true);
        session.newStream(headersFrame, new Promise.Adapter<>(), new Stream.Listener.Adapter()
        {
            @Override
            public void onHeaders(Stream stream, HeadersFrame frame)
            {
                if (frame.isEndStream())
                    latch.countDown();
            }
        });

        assertTrue(latch.await(5, TimeUnit.SECONDS));
    }
}
