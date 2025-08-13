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

package org.eclipse.jetty.http2.client;

import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http2.ErrorCode;
import org.eclipse.jetty.http2.HTTP2Session;
import org.eclipse.jetty.http2.api.Session;
import org.eclipse.jetty.http2.api.Stream;
import org.eclipse.jetty.http2.api.server.ServerSessionListener;
import org.eclipse.jetty.http2.frames.GoAwayFrame;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import org.eclipse.jetty.http2.frames.SettingsFrame;
import org.eclipse.jetty.http2.parser.WindowRateControl;
import org.eclipse.jetty.http2.server.AbstractHTTP2ServerConnectionFactory;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Promise;
import org.junit.jupiter.api.Test;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TriggeredResetTest extends AbstractTest
{
    @Test
    public void testClosedDataFrameTriggersReset() throws Exception
    {
        CountDownLatch settingsLatch = new CountDownLatch(2);
        start(new ServerSessionListener.Adapter()
        {
            @Override
            public void onSettings(Session session, SettingsFrame frame)
            {
                settingsLatch.countDown();
            }

            @Override
            public Stream.Listener onNewStream(Stream stream, HeadersFrame frame)
            {
                MetaData.Response response = new MetaData.Response(HttpVersion.HTTP_2, HttpStatus.OK_200, null, HttpFields.EMPTY, -1);
                stream.headers(new HeadersFrame(stream.getId(), response, null, true), Callback.NOOP);
                return null;
            }
        });
        AbstractHTTP2ServerConnectionFactory h2 = connector.getBean(AbstractHTTP2ServerConnectionFactory.class);
        int maxEventRate = 5;
        h2.setRateControlFactory(new WindowRateControl.Factory(maxEventRate));

        AtomicInteger errorRef = new AtomicInteger();
        Session session = newClient(new Session.Listener.Adapter()
        {
            @Override
            public void onSettings(Session session, SettingsFrame frame)
            {
                settingsLatch.countDown();
            }

            @Override
            public void onGoAway(Session session, GoAwayFrame frame)
            {
                errorRef.set(frame.getError());
            }
        });

        assertTrue(settingsLatch.await(5, TimeUnit.SECONDS));

        CountDownLatch responseLatch = new CountDownLatch(1);
        Promise.Completable<Stream> promise = new Promise.Completable<>();
        session.newStream(new HeadersFrame(newRequest("GET", HttpFields.EMPTY), null, true), promise, new Stream.Listener.Adapter()
            {
                @Override
                public void onHeaders(Stream stream, HeadersFrame frame)
                {
                    responseLatch.countDown();
                }
            });
        Stream stream = promise.get(5, TimeUnit.SECONDS);

        assertThat(stream.getId(), equalTo(1));
        assertTrue(responseLatch.await(5, TimeUnit.SECONDS));

        // Send many DATA frames for that stream to trigger a reset from the server.
        // DATA format in bytes: LEN(3) TYPE(1) FLAGS(1) STREAM_ID(4)
        // Empty DATA frame with END_STREAM=true for STREAM_ID=1.
        byte[] dataFrameBytes = {0, 0, 0, 0, 1, 0, 0, 0, 1};
        int dataFrameCount = 2 * maxEventRate;
        ByteBuffer byteBuffer = ByteBuffer.allocate(dataFrameBytes.length * dataFrameCount);
        for (int i = 0; i < dataFrameCount; ++i)
        {
            byteBuffer.put(dataFrameBytes);
        }
        byteBuffer.flip();
        ((HTTP2Session)session).getEndPoint().write(Callback.NOOP, byteBuffer);

        await().atMost(5, TimeUnit.SECONDS).until(errorRef::get, equalTo(ErrorCode.ENHANCE_YOUR_CALM_ERROR.code));
    }

    @Test
    public void testWindowUpdateExceededTriggersReset() throws Exception
    {
        CountDownLatch settingsLatch = new CountDownLatch(2);
        start(new ServerSessionListener.Adapter()
        {
            @Override
            public void onSettings(Session session, SettingsFrame frame)
            {
                settingsLatch.countDown();
            }
        });
        AbstractHTTP2ServerConnectionFactory h2 = connector.getBean(AbstractHTTP2ServerConnectionFactory.class);
        int maxEventRate = 5;
        h2.setRateControlFactory(new WindowRateControl.Factory(maxEventRate));

        AtomicInteger errorRef = new AtomicInteger();
        Session session = newClient(new Session.Listener.Adapter()
        {
            @Override
            public void onSettings(Session session, SettingsFrame frame)
            {
                settingsLatch.countDown();
            }

            @Override
            public void onGoAway(Session session, GoAwayFrame frame)
            {
                errorRef.set(frame.getError());
            }
        });

        assertTrue(settingsLatch.await(5, TimeUnit.SECONDS));

        // The request is not responded, so the stream remains alive.
        Promise.Completable<Stream> promise = new Promise.Completable<>();
        session.newStream(new HeadersFrame(newRequest("GET", HttpFields.EMPTY), null, true), promise, new Stream.Listener.Adapter() {});
        Stream stream = promise.get(5, TimeUnit.SECONDS);

        assertThat(stream.getId(), equalTo(1));

        // Now send WINDOW_UPDATE frames to overflow 2^31-1.
        // WINDOW_UPDATE format in bytes: LEN(3) TYPE(1) FLAGS(1) STREAM_ID(4) WINDOW(4)
        byte[] dataFrameBytes = {0, 0, 4, 8, 0, 0, 0, 0, 1, -1, -1, -1, -1};
        int dataFrameCount = 2 * maxEventRate;
        ByteBuffer byteBuffer = ByteBuffer.allocate(dataFrameBytes.length * dataFrameCount);
        for (int i = 0; i < dataFrameCount; ++i)
        {
            byteBuffer.put(dataFrameBytes);
        }
        byteBuffer.flip();
        ((HTTP2Session)session).getEndPoint().write(Callback.NOOP, byteBuffer);

        await().atMost(5, TimeUnit.SECONDS).until(errorRef::get, equalTo(ErrorCode.ENHANCE_YOUR_CALM_ERROR.code));
    }
}
