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
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.quic.api.Session;
import org.eclipse.jetty.quic.api.frames.TransportParameters;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Promise;
import org.junit.jupiter.api.Test;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
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

        Thread.sleep(1000);

        // Establish a second connection, it should be resumed (zero-RTT with no early data).
        AtomicReference<TransportParameters> serverTransportParametersRef = new AtomicReference<>();
        Session secondSession = Promise.Completable.<Session>with(p ->
            client.connect(new InetSocketAddress("localhost", connector.getLocalPort()), BufferUtil.EMPTY_BUFFER, new Session.Listener()
            {
                @Override
                public void onTransportParameters(Session session, TransportParameters parameters)
                {
                    serverTransportParametersRef.set(parameters);
                }
            }, p)
        ).get(5, TimeUnit.SECONDS);

        // TODO: the test is broken because the server always sends the updated transport parameters.
        //  It uses the initial transport parameters stored in the session ticket only for the early data (e.g. bi_stream_max_data)

        TransportParameters serverTransportParameters = serverTransportParametersRef.get();
        assertThat(serverTransportParameters.get(TransportParameters.Ids.INITIAL_MAX_STREAMS_BIDIRECTIONAL), equalTo(bidiMaxStreams));
    }
}
