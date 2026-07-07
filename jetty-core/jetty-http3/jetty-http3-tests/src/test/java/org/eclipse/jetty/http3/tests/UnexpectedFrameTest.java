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

import org.eclipse.jetty.http3.HTTP3ErrorCode;
import org.eclipse.jetty.http3.HTTP3Session;
import org.eclipse.jetty.http3.api.Session;
import org.eclipse.jetty.http3.frames.DataFrame;
import org.eclipse.jetty.http3.frames.GoAwayFrame;
import org.eclipse.jetty.http3.frames.SettingsFrame;
import org.eclipse.jetty.quic.common.ProtocolSession;
import org.eclipse.jetty.quic.common.StreamEndPoint;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.buffer.ReadableBuffer;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class UnexpectedFrameTest extends AbstractClientServerTest
{
    @ParameterizedTest
    @MethodSource("transports")
    public void testDataBeforeHeaders(TransportType transportType) throws Exception
    {
        CountDownLatch settingsLatch = new CountDownLatch(2);
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
                assertEquals(HTTP3ErrorCode.FRAME_UNEXPECTED_ERROR.code(), error);
                serverFailureLatch.countDown();
            }
        });

        CountDownLatch clientGoAwayLatch = new CountDownLatch(1);
        CountDownLatch clientDisconnectLatch = new CountDownLatch(1);
        HTTP3Session clientSession = (HTTP3Session)newSession(new Session.Client.Listener()
        {
            @Override
            public void onSettings(Session session, SettingsFrame frame)
            {
                settingsLatch.countDown();
            }

            @Override
            public void onGoAway(Session session, GoAwayFrame frame)
            {
                clientGoAwayLatch.countDown();
            }

            @Override
            public void onDisconnect(Session session, long error, String reason)
            {
                assertEquals(HTTP3ErrorCode.FRAME_UNEXPECTED_ERROR.code(), error);
                clientDisconnectLatch.countDown();
            }
        });

        assertTrue(settingsLatch.await(5, TimeUnit.SECONDS));

        ProtocolSession protocolSession = clientSession.getProtocolSession();
        var quicStream = protocolSession.getSession().newStream(0, null);
        StreamEndPoint streamEndPoint = protocolSession.createStreamEndPoint(quicStream, protocolSession::openStreamEndPoint);
        clientSession.writeMessageFrame(streamEndPoint, new DataFrame(ReadableBuffer.allocate(128, false), false), Callback.NOOP);

        assertTrue(serverFailureLatch.await(5, TimeUnit.SECONDS));
        assertTrue(clientGoAwayLatch.await(5, TimeUnit.SECONDS));
        assertTrue(clientDisconnectLatch.await(5, TimeUnit.SECONDS));

        await().atMost(1, TimeUnit.SECONDS).until(clientSession::isClosed);
    }
}
