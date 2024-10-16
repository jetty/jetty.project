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

package org.eclipse.jetty.http2.tests;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http2.HTTP2Session;
import org.eclipse.jetty.http2.api.Session;
import org.eclipse.jetty.http2.api.Stream;
import org.eclipse.jetty.http2.api.server.ServerSessionListener;
import org.eclipse.jetty.http2.frames.DataFrame;
import org.eclipse.jetty.http2.frames.Frame;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import org.eclipse.jetty.http2.frames.SettingsFrame;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.FuturePromise;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class InterleavingTest extends AbstractTest
{
    private static final Logger logger = LoggerFactory.getLogger(InterleavingTest.class);

    @Test
    public void testInterleaving() throws Exception
    {
        CountDownLatch serverStreamsLatch = new CountDownLatch(2);
        List<Stream> serverStreams = new ArrayList<>();
        start(new ServerSessionListener()
        {
            @Override
            public Stream.Listener onNewStream(Stream stream, HeadersFrame frame)
            {
                serverStreams.add(stream);
                serverStreamsLatch.countDown();
                return null;
            }
        });

        int maxFrameSize = Frame.DEFAULT_MAX_SIZE + 1;
        Session session = newClientSession(new Session.Listener()
        {
            @Override
            public Map<Integer, Integer> onPreface(Session session)
            {
                Map<Integer, Integer> settings = new HashMap<>();
                settings.put(SettingsFrame.MAX_FRAME_SIZE, maxFrameSize);
                return settings;
            }
        });

        CountDownLatch clientsLatch = new CountDownLatch(2);
        BlockingQueue<Stream.Data> dataQueue = new LinkedBlockingDeque<>();
        Stream.Listener streamListener = new Stream.Listener.NonBlocking()
        {
            @Override
            public void onDataAvailable(Stream stream)
            {
                Stream.Data data = stream.readData();
                logger.info("onDataAvailable {}", data);
                // Do not release.
                dataQueue.offer(data);
                if (!data.frame().isEndStream())
                    stream.demand();
                else
                    clientsLatch.countDown();
            }
        };

        HeadersFrame headersFrame1 = new HeadersFrame(newRequest("GET", HttpFields.EMPTY), null, true);
        FuturePromise<Stream> streamPromise1 = new FuturePromise<>();
        session.newStream(headersFrame1, streamPromise1, streamListener);
        streamPromise1.get(5, TimeUnit.SECONDS);

        HeadersFrame headersFrame2 = new HeadersFrame(newRequest("GET", HttpFields.EMPTY), null, true);
        FuturePromise<Stream> streamPromise2 = new FuturePromise<>();
        session.newStream(headersFrame2, streamPromise2, streamListener);
        streamPromise2.get(5, TimeUnit.SECONDS);

        assertTrue(serverStreamsLatch.await(5, TimeUnit.SECONDS));

        Thread.sleep(1000);

        Stream serverStream1 = serverStreams.get(0);
        Stream serverStream2 = serverStreams.get(1);
        MetaData.Response response1 = new MetaData.Response(HttpStatus.OK_200, null, HttpVersion.HTTP_2, HttpFields.EMPTY);
        serverStream1.headers(new HeadersFrame(serverStream1.getId(), response1, null, false), Callback.NOOP);

        Random random = new Random();
        byte[] content1 = new byte[2 * ((HTTP2Session)serverStream1.getSession()).updateSendWindow(0)];
        random.nextBytes(content1);
        byte[] content2 = new byte[2 * ((HTTP2Session)serverStream2.getSession()).updateSendWindow(0)];
        random.nextBytes(content2);

        MetaData.Response response2 = new MetaData.Response(HttpStatus.OK_200, null, HttpVersion.HTTP_2, HttpFields.EMPTY);
        serverStream2.headers(new HeadersFrame(serverStream2.getId(), response2, null, false), new Callback()
        {
            @Override
            public void succeeded()
            {
                // Write data for both streams from within the callback so that they get queued together.

                ByteBuffer buffer1 = ByteBuffer.wrap(content1);
                serverStream1.data(new DataFrame(serverStream1.getId(), buffer1, true), NOOP);

                ByteBuffer buffer2 = ByteBuffer.wrap(content2);
                serverStream2.data(new DataFrame(serverStream2.getId(), buffer2, true), NOOP);
            }
        });

        assertTrue(clientsLatch.await(5, TimeUnit.SECONDS));

        List<StreamLength> streamLengthList = dataQueue.stream()
            .map(Stream.Data::frame)
            .map(frame -> new StreamLength(frame.getStreamId(), frame.remaining()))
            .toList();
        dataQueue.forEach(Stream.Data::release);

        logger.debug("data queue {}", streamLengthList);

        // Coalesce the data queue into a sequence of stream frames to verify interleaving.
        AtomicInteger prevStreamId = new AtomicInteger();
        List<Integer> interleaveSequence = streamLengthList.stream()
            .map(StreamLength::streamId)
            .filter(streamId ->
            {
                boolean keep = prevStreamId.get() != streamId;
                prevStreamId.set(streamId);
                return keep;
            })
            .toList();

        logger.debug("interleave sequence {}", interleaveSequence);

        // A non-interleaved sequence would be just [1,3].
        // Make sure that we have at least some interleave sequence like [1,3,1,3,1,3,...].
        assertThat(interleaveSequence.size(), greaterThan(4));
    }

    private record StreamLength(int streamId, int length)
    {
        @Override
        public String toString()
        {
            return String.format("(%d,%d)", streamId, length);
        }
    }
}
