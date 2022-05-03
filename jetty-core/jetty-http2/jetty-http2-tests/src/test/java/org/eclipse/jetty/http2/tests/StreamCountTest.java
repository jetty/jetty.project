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

package org.eclipse.jetty.http2.client;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http2.HTTP2Session;
import org.eclipse.jetty.http2.api.Session;
import org.eclipse.jetty.http2.api.Stream;
import org.eclipse.jetty.http2.api.server.ServerSessionListener;
import org.eclipse.jetty.http2.frames.DataFrame;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import org.eclipse.jetty.http2.frames.ResetFrame;
import org.eclipse.jetty.http2.frames.SettingsFrame;
import org.eclipse.jetty.http2.generator.Generator;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.FuturePromise;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class StreamCountTest extends AbstractTest
{
    @Test
    public void testServerAllowsOneStreamEnforcedByClient() throws Exception
    {
        start(new ServerSessionListener.Adapter()
        {
            @Override
            public Map<Integer, Integer> onPreface(Session session)
            {
                Map<Integer, Integer> settings = new HashMap<>();
                settings.put(SettingsFrame.MAX_CONCURRENT_STREAMS, 1);
                return settings;
            }

            @Override
            public Stream.Listener onNewStream(Stream stream, HeadersFrame frame)
            {
                return new Stream.Listener.Adapter()
                {
                    @Override
                    public void onData(Stream stream, DataFrame frame, Callback callback)
                    {
                        if (frame.isEndStream())
                        {
                            MetaData.Response metaData = new MetaData.Response(HttpVersion.HTTP_2, 200, HttpFields.EMPTY);
                            stream.headers(new HeadersFrame(stream.getId(), metaData, null, true), callback);
                        }
                        else
                        {
                            callback.succeeded();
                        }
                    }
                };
            }
        });

        CountDownLatch settingsLatch = new CountDownLatch(1);
        Session session = newClient(new Session.Listener.Adapter()
        {
            @Override
            public void onSettings(Session session, SettingsFrame frame)
            {
                settingsLatch.countDown();
            }
        });

        assertTrue(settingsLatch.await(5, TimeUnit.SECONDS));

        MetaData.Request metaData = newRequest("GET", HttpFields.EMPTY);
        HeadersFrame frame1 = new HeadersFrame(metaData, null, false);
        FuturePromise<Stream> streamPromise1 = new FuturePromise<>();
        CountDownLatch responseLatch = new CountDownLatch(1);
        session.newStream(frame1, streamPromise1, new Stream.Listener.Adapter()
        {
            @Override
            public void onHeaders(Stream stream, HeadersFrame frame)
            {
                if (frame.isEndStream())
                    responseLatch.countDown();
            }
        });
        Stream stream1 = streamPromise1.get(5, TimeUnit.SECONDS);

        HeadersFrame frame2 = new HeadersFrame(metaData, null, false);
        FuturePromise<Stream> streamPromise2 = new FuturePromise<>();
        session.newStream(frame2, streamPromise2, new Stream.Listener.Adapter());

        assertThrows(ExecutionException.class,
            () -> streamPromise2.get(5, TimeUnit.SECONDS));

        stream1.data(new DataFrame(stream1.getId(), BufferUtil.EMPTY_BUFFER, true), Callback.NOOP);
        assertTrue(responseLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testServerAllowsOneStreamEnforcedByServer() throws Exception
    {
        start(new ServerSessionListener.Adapter()
        {
            @Override
            public Stream.Listener onNewStream(Stream stream, HeadersFrame frame)
            {
                HTTP2Session session = (HTTP2Session)stream.getSession();
                session.setMaxRemoteStreams(1);

                return new Stream.Listener.Adapter()
                {
                    @Override
                    public void onData(Stream stream, DataFrame frame, Callback callback)
                    {
                        if (frame.isEndStream())
                        {
                            MetaData.Response metaData = new MetaData.Response(HttpVersion.HTTP_2, 200, HttpFields.EMPTY);
                            stream.headers(new HeadersFrame(stream.getId(), metaData, null, true), callback);
                        }
                        else
                        {
                            callback.succeeded();
                        }
                    }
                };
            }
        });

        CountDownLatch sessionResetLatch = new CountDownLatch(2);
        Session session = newClient(new Session.Listener.Adapter()
        {
            @Override
            public void onReset(Session session, ResetFrame frame)
            {
                sessionResetLatch.countDown();
            }
        });

        MetaData.Request metaData = newRequest("GET", HttpFields.EMPTY);
        HeadersFrame frame1 = new HeadersFrame(metaData, null, false);
        FuturePromise<Stream> streamPromise1 = new FuturePromise<>();
        CountDownLatch responseLatch = new CountDownLatch(1);
        session.newStream(frame1, streamPromise1, new Stream.Listener.Adapter()
        {
            @Override
            public void onHeaders(Stream stream, HeadersFrame frame)
            {
                if (frame.isEndStream())
                    responseLatch.countDown();
            }
        });

        Stream stream1 = streamPromise1.get(5, TimeUnit.SECONDS);

        HeadersFrame frame2 = new HeadersFrame(metaData, null, false);
        FuturePromise<Stream> streamPromise2 = new FuturePromise<>();
        AtomicReference<CountDownLatch> resetLatch = new AtomicReference<>(new CountDownLatch(1));
        session.newStream(frame2, streamPromise2, new Stream.Listener.Adapter()
        {
            @Override
            public void onReset(Stream stream, ResetFrame frame)
            {
                resetLatch.get().countDown();
            }
        });

        Stream stream2 = streamPromise2.get(5, TimeUnit.SECONDS);
        assertTrue(resetLatch.get().await(5, TimeUnit.SECONDS));

        // Reset the latch and send a DATA frame, it should be dropped
        // by the client because the stream has already been reset.
        resetLatch.set(new CountDownLatch(1));
        stream2.data(new DataFrame(stream2.getId(), BufferUtil.EMPTY_BUFFER, true), Callback.NOOP);
        // Must not receive another RST_STREAM.
        assertFalse(resetLatch.get().await(1, TimeUnit.SECONDS));

        // Simulate a client sending both HEADERS and DATA frames at the same time.
        // The server should send a RST_STREAM for the HEADERS.
        // For the server, dropping the DATA frame is too costly so it sends another RST_STREAM.
        int streamId3 = stream2.getId() + 2;
        HeadersFrame frame3 = new HeadersFrame(streamId3, metaData, null, false);
        DataFrame data3 = new DataFrame(streamId3, BufferUtil.EMPTY_BUFFER, true);
        Generator generator = ((HTTP2Session)session).getGenerator();
        ByteBufferPool.Lease lease = new ByteBufferPool.Lease(generator.getByteBufferPool());
        generator.control(lease, frame3);
        generator.data(lease, data3, data3.remaining());
        ((HTTP2Session)session).getEndPoint().write(Callback.NOOP, lease.getByteBuffers().toArray(new ByteBuffer[0]));
        // Expect 2 RST_STREAM frames.
        assertTrue(sessionResetLatch.await(5, TimeUnit.SECONDS));

        stream1.data(new DataFrame(stream1.getId(), BufferUtil.EMPTY_BUFFER, true), Callback.NOOP);
        assertTrue(responseLatch.await(5, TimeUnit.SECONDS));
    }
}
