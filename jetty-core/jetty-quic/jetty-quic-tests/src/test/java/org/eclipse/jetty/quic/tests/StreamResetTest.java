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

package org.eclipse.jetty.quic.tests;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.quic.api.Session;
import org.eclipse.jetty.quic.api.Stream;
import org.eclipse.jetty.quic.api.frames.ConnectionCloseFrame;
import org.eclipse.jetty.quic.api.frames.Frame;
import org.eclipse.jetty.quic.api.frames.ResetFrame;
import org.eclipse.jetty.quic.common.QuicSession;
import org.eclipse.jetty.quic.common.QuicStream;
import org.eclipse.jetty.quic.util.ErrorCode;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Promise;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.awaitility.Awaitility.await;
import static org.eclipse.jetty.util.thread.Invocable.InvocationType.NON_BLOCKING;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class StreamResetTest extends AbstractQuicTest
{
    @Test
    public void testResetReceivedForSendOnlyStream() throws Exception
    {
        AtomicReference<Session> serverSessionRef = new AtomicReference<>();
        CountDownLatch serverCloseLatch = new CountDownLatch(1);
        CountDownLatch serverDisconnectLatch = new CountDownLatch(1);
        start(() -> new Session.Listener()
        {
            @Override
            public Stream.Listener onNewStream(Session session, Frame.WithStreamId frame)
            {
                serverSessionRef.set(session);
                return new Stream.Listener()
                {
                    @Override
                    public void onNewStream(Stream stream, Frame.WithStreamId frame)
                    {
                        // Sending a reset to a receive-only stream is an error.
                        stream.reset(ErrorCode.NO_ERROR.code(), Callback.from(NON_BLOCKING, x ->
                        {
                            assertNotNull(x);
                            // Bypass the Stream API to actually send the reset frame.
                            ((QuicSession)stream.getSession()).reset((QuicStream)stream, new ResetFrame(stream.getId(), ErrorCode.NO_ERROR.code(), -1), Callback.NOOP);
                        }));
                    }
                };
            }

            @Override
            public void onClose(Session session, ConnectionCloseFrame frame)
            {
                serverCloseLatch.countDown();
            }

            @Override
            public void onDisconnect(Session session)
            {
                serverDisconnectLatch.countDown();
            }
        });

        CountDownLatch clientDisconnectLatch = new CountDownLatch(1);
        Promise.Completable<Session> promise = new Promise.Completable<>();
        client.connect(new InetSocketAddress("localhost", connector.getLocalPort()), new Session.Listener()
        {
            @Override
            public void onDisconnect(Session session)
            {
                clientDisconnectLatch.countDown();
            }
        }, promise);
        Session clientSession = promise.get(5, SECONDS);

        long streamId = clientSession.newStreamId(false);
        Stream clientStream = clientSession.newStream(streamId, new Stream.Listener() {});
        clientStream.data(false, RetainableByteBuffer.wrap(ByteBuffer.allocate(1)), Callback.NOOP);

        Session serverSession = await().atMost(5, SECONDS).until(serverSessionRef::get, notNullValue());

        assertTrue(serverCloseLatch.await(5, SECONDS));
        assertTrue(serverDisconnectLatch.await(5, SECONDS));
        assertTrue(clientDisconnectLatch.await(5, SECONDS));

        await().atMost(5, SECONDS).until(serverSession::getStreams, Matchers.empty());
        await().atMost(5, SECONDS).until(clientSession::getStreams, Matchers.empty());

    }
}
