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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.quic.api.Session;
import org.eclipse.jetty.quic.api.frames.TransportParameters;
import org.eclipse.jetty.quic.common.EncryptionLevel;
import org.eclipse.jetty.quic.common.QuicSession;
import org.eclipse.jetty.quic.common.tls.TLSEngine;
import org.eclipse.jetty.tls.CertificateMessage;
import org.eclipse.jetty.tls.Message;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Promise;
import org.junit.jupiter.api.Test;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ZeroRTTQuicTest extends AbstractQuicTest
{
    @Test
    public void testZeroRTT() throws Exception
    {
        start(() -> new Session.Listener() {});

        // Establish a first connection.
        List<Message> incomingTLSMessages = new ArrayList<>();
        Promise.Completable.<Session>with(p ->
            client.connect(new InetSocketAddress("localhost", connector.getLocalPort()), null, new Session.Listener()
            {
                @Override
                public void onPrepare(Session session, TransportParameters transportParameters)
                {
                    ((QuicSession)session).getTLSEngine().addMessageListener(new TLSEngine.MessageListener()
                    {
                        @Override
                        public void onIncomingMessage(EncryptionLevel encryptionLevel, Message message)
                        {
                            incomingTLSMessages.add(message);
                        }
                    });
                }
            }, p)
        ).get(5, TimeUnit.SECONDS);

        // Full TLS handshake, must have received the certificate.
        assertTrue(incomingTLSMessages.stream().anyMatch(m -> m instanceof CertificateMessage));
        incomingTLSMessages.clear();

        // Make sure there is a zero-rtt entry to resume the second connection.
        await().atMost(5, TimeUnit.SECONDS).until(client.getZeroRTTStore()::size, equalTo(1));

        // Establish a second connection, it should be resumed (zero-RTT with no early data).
        Promise.Completable.<Session>with(p ->
            client.connect(new InetSocketAddress("localhost", connector.getLocalPort()), BufferUtil.EMPTY_BUFFER, new Session.Listener()
            {
                @Override
                public void onPrepare(Session session, TransportParameters transportParameters)
                {
                    ((QuicSession)session).getTLSEngine().addMessageListener(new TLSEngine.MessageListener()
                    {
                        @Override
                        public void onIncomingMessage(EncryptionLevel encryptionLevel, Message message)
                        {
                            incomingTLSMessages.add(message);
                        }
                    });
                }
            }, p)
        ).get(5, TimeUnit.SECONDS);

        assertTrue(incomingTLSMessages.stream().noneMatch(m -> m instanceof CertificateMessage));
    }
}
