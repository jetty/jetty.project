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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.quic.api.Session;
import org.eclipse.jetty.quic.api.Stream;
import org.eclipse.jetty.quic.api.frames.Frame;
import org.eclipse.jetty.quic.api.frames.ResetFrame;
import org.eclipse.jetty.quic.api.frames.StopSendingFrame;
import org.eclipse.jetty.util.Promise;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class StreamIdleTimeoutTest extends AbstractQuicTest
{
    @Test
    public void testClientStreamIdleTimeout() throws Exception
    {
        long idleTimeout = 1000;
        AtomicReference<Session> serverSessionRef = new AtomicReference<>();
        CountDownLatch serverStopSendingLatch = new CountDownLatch(1);
        CountDownLatch serverResetLatch = new CountDownLatch(1);
        start(() -> new Session.Listener()
        {
            @Override
            public Stream.Listener onNewStream(Session session, Frame.WithStreamId frame)
            {
                serverSessionRef.set(session);
                return new Stream.Listener()
                {
                    @Override
                    public void onStopSending(Stream stream, StopSendingFrame frame)
                    {
                        serverStopSendingLatch.countDown();
                    }

                    @Override
                    public void onReset(Stream stream, ResetFrame frame)
                    {
                        serverResetLatch.countDown();
                    }
                };
            }
        });
        connector.getServerQuicConfiguration().setStreamIdleTimeout(10 * idleTimeout);

        client.getClientQuicConfiguration().setStreamIdleTimeout(idleTimeout);
        Promise.Completable<Session> promise = new Promise.Completable<>();
        client.connect(new InetSocketAddress("localhost", connector.getLocalPort()), new Session.Listener() {}, promise);
        Session clientSession = promise.get(5, SECONDS);

        CountDownLatch clientResetLatch = new CountDownLatch(1);
        long streamId = clientSession.newStreamId(true);
        Stream clientStream = clientSession.newStream(streamId, new Stream.Listener()
        {
            @Override
            public void onReset(Stream stream, ResetFrame frame)
            {
                clientResetLatch.countDown();
            }
        });
        clientStream.data(true, RetainableByteBuffer.EMPTY, Promise.Invocable.noop());

        Session serverSession = await().atMost(5, SECONDS).until(serverSessionRef::get, notNullValue());

        assertTrue(serverStopSendingLatch.await(5, SECONDS));
        assertTrue(serverResetLatch.await(5, SECONDS));
        assertTrue(clientResetLatch.await(5, SECONDS));

        await().atMost(5, SECONDS).until(serverSession::getStreams, Matchers.empty());
        await().atMost(5, SECONDS).until(clientSession::getStreams, Matchers.empty());
    }
}
