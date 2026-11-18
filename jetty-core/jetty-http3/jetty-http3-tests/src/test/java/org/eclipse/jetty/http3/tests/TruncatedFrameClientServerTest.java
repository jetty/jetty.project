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

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import org.eclipse.jetty.http3.HTTP3ErrorCode;
import org.eclipse.jetty.http3.HTTP3Session;
import org.eclipse.jetty.http3.HTTP3Stream;
import org.eclipse.jetty.http3.api.Session;
import org.eclipse.jetty.http3.api.Stream;
import org.eclipse.jetty.http3.frames.HeadersFrame;
import org.eclipse.jetty.http3.frames.SettingsFrame;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.quic.common.ProtocolSession;
import org.eclipse.jetty.quic.common.StreamEndPoint;
import org.eclipse.jetty.util.Blocker;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.buffer.RetainableByteBuffer;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <p>Tests that a stream that terminates cleanly in the middle of
 * a frame is an {@code H3_FRAME_ERROR}, as per RFC 9114, section 7.1.</p>
 */
public class TruncatedFrameClientServerTest extends AbstractClientServerTest
{
    @ParameterizedTest
    @MethodSource("transports")
    public void testHeadersTruncatedByLastBytes(TransportType transportType) throws Exception
    {
        testTruncatedFrame(transportType, streamEndPoint ->
            streamEndPoint.write(true, RetainableByteBuffer.wrap(new byte[]{0x01, 0x10, 0x01, 0x02}), Callback.NOOP));
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testHeadersTruncatedByEmptyLast(TransportType transportType) throws Exception
    {
        testTruncatedFrame(transportType, streamEndPoint ->
            streamEndPoint.write(false, RetainableByteBuffer.wrap(new byte[]{0x01, 0x10, 0x01, 0x02}), Callback.from(() ->
                streamEndPoint.write(true, RetainableByteBuffer.empty(), Callback.NOOP))));
    }

    private void testTruncatedFrame(TransportType transportType, Consumer<StreamEndPoint> writer) throws Exception
    {
        CountDownLatch settingsLatch = new CountDownLatch(2);
        AtomicLong serverError = new AtomicLong();
        CountDownLatch serverFailureLatch = new CountDownLatch(1);
        start(transportType, new Session.Server.Listener()
        {
            @Override
            public void onSettings(Session session, SettingsFrame frame)
            {
                settingsLatch.countDown();
            }

            @Override
            public void onFailure(Session session, long error, String reason, Throwable failure)
            {
                serverError.set(error);
                serverFailureLatch.countDown();
            }
        });

        AtomicLong clientError = new AtomicLong();
        CountDownLatch clientDisconnectLatch = new CountDownLatch(1);
        HTTP3Session clientSession = (HTTP3Session)newSession(new Session.Client.Listener()
        {
            @Override
            public void onSettings(Session session, SettingsFrame frame)
            {
                settingsLatch.countDown();
            }

            @Override
            public void onDisconnect(Session session, long error, String reason)
            {
                clientError.set(error);
                clientDisconnectLatch.countDown();
            }
        });

        assertTrue(settingsLatch.await(5, TimeUnit.SECONDS));

        ProtocolSession protocolSession = clientSession.getProtocolSession();
        var quicStream = protocolSession.getSession().newStream(0, null);
        writer.accept(protocolSession.createStreamEndPoint(quicStream, protocolSession::openStreamEndPoint));

        assertTrue(serverFailureLatch.await(5, TimeUnit.SECONDS));
        assertEquals(HTTP3ErrorCode.FRAME_ERROR.code(), serverError.get());
        assertTrue(clientDisconnectLatch.await(5, TimeUnit.SECONDS));
        assertEquals(HTTP3ErrorCode.FRAME_ERROR.code(), clientError.get());
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testDataTruncatedIsNotEndOfContent(TransportType transportType) throws Exception
    {
        AtomicBoolean serverEOF = new AtomicBoolean();
        AtomicLong serverError = new AtomicLong();
        CountDownLatch serverFailureLatch = new CountDownLatch(1);
        start(transportType, new Session.Server.Listener()
        {
            @Override
            public Stream.Server.Listener onRequest(Session.Server session, HeadersFrame frame)
            {
                return new Stream.Server.Listener()
                {
                    @Override
                    public void onRequest(Stream.Server stream, HeadersFrame frame)
                    {
                        stream.demand();
                    }

                    @Override
                    public void onDataAvailable(Stream.Server stream)
                    {
                        try (Content.Chunk chunk = stream.read())
                        {
                            if (chunk != null && chunk.isLast() && !Content.Chunk.isFailure(chunk))
                            {
                                serverEOF.set(true);
                                return;
                            }
                            if (!Content.Chunk.isFailure(chunk))
                                stream.demand();
                        }
                    }
                };
            }

            @Override
            public void onFailure(Session session, long error, String reason, Throwable failure)
            {
                serverError.set(error);
                serverFailureLatch.countDown();
            }
        });

        Session.Client clientSession = newSession(new Session.Client.Listener() {});
        HeadersFrame request = new HeadersFrame(newRequest("/"), false);
        Stream stream = Blocker.blockWithPromise(5, TimeUnit.SECONDS, p -> clientSession.newRequest(request, new Stream.Client.Listener() {}, p));

        // A DATA frame that declares 16 bytes, but only has 2, followed by the FIN.
        StreamEndPoint streamEndPoint = ((HTTP3Stream)stream).getStreamEndPoint();
        streamEndPoint.write(true, RetainableByteBuffer.wrap(new byte[]{0x00, 0x10, 0x01, 0x02}), Callback.NOOP);

        assertTrue(serverFailureLatch.await(5, TimeUnit.SECONDS));
        assertEquals(HTTP3ErrorCode.FRAME_ERROR.code(), serverError.get());
        assertFalse(serverEOF.get());
    }
}
