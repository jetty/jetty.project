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
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.quic.api.Session;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Promise;
import org.junit.jupiter.api.Test;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.equalTo;

public class ZeroRTTQuicTest extends AbstractQuicTest
{
    @Test
    public void testZeroRTT() throws Exception
    {
        start(() -> new Session.Listener() {});

        // Set a transport parameter to verify it won't change later.
        long bidiMaxStreams = 1;
        connector.getServerQuicConfiguration().setBidirectionalMaxStreams(bidiMaxStreams);

        // Establish a first connection.
        Session firstSession = Promise.Completable.<Session>with(p ->
            client.connect(new InetSocketAddress("localhost", connector.getLocalPort()), null, new Session.Listener() {}, p)
        ).get(5, TimeUnit.SECONDS);

        await().atMost(5, TimeUnit.SECONDS).until(client.getZeroRTTStore()::size, equalTo(1));

        // Change a server transport parameter to verify that it is not used,
        // as the one from the previous connection should be used instead.
        connector.getServerQuicConfiguration().setBidirectionalMaxStreams(bidiMaxStreams + 1);

        // Establish a second connection, it should be resumed (zero-RTT with no early data).
        Session secondSession = Promise.Completable.<Session>with(p ->
            client.connect(new InetSocketAddress("localhost", connector.getLocalPort()), BufferUtil.EMPTY_BUFFER, new Session.Listener() {}, p)
        ).get(5, TimeUnit.SECONDS);



    }
}
