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

package org.eclipse.jetty.quic.server.internal.tls;

import org.eclipse.jetty.quic.common.packets.PacketProtector;
import org.eclipse.jetty.quic.common.tls.TLSEngine;
import org.eclipse.jetty.tls.Message;
import org.eclipse.jetty.tls.common.generator.MessageGenerator;

/// The server-side implementation of QUIC encryption/decryption,
/// and the server-side TLS state machine necessary for QUIC.
public class ServerTLSEngine extends TLSEngine implements MessageGenerator.Listener
{
    public ServerTLSEngine(PacketProtector packetProtector)
    {
        super(packetProtector, false);
    }

    @Override
    public void onMessageGenerated(Message message)
    {
        // TODO: feed TranscriptHash.
    }

    @Override
    public void onMessageParsed(Message message)
    {
        // TODO: feed TranscriptHash (see client).
    }
}
