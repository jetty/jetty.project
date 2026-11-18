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

package org.eclipse.jetty.quic.common.internal.packets;

import org.eclipse.jetty.quic.api.QuicVersion;
import org.eclipse.jetty.quic.common.packets.Packet;
import org.eclipse.jetty.quic.common.packets.RetryPacket;
import org.eclipse.jetty.util.buffer.RetainableByteBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RetryPacketParser implements PacketParser
{
    private static final Logger LOG = LoggerFactory.getLogger(RetryPacketParser.class);

    @Override
    public Packet parse(RetainableByteBuffer buffer) throws Exception
    {
        if (LOG.isDebugEnabled())
            LOG.debug("parsing RetryPacket {}", buffer);

        long packetLength = buffer.remaining();

        // Skip the first byte.
        buffer.get();

        int versionCode = buffer.getInt();
        QuicVersion quicVersion = QuicVersion.from(versionCode);

        int length = buffer.getByteAsInt();
        byte[] dstConnectionId = new byte[length];
        buffer.get(dstConnectionId);

        length = buffer.getByteAsInt();
        byte[] srcConnectionId = new byte[length];
        buffer.get(srcConnectionId);

        // The token length is implicit, as the integrity is 16 bytes.
        length = (int)buffer.remaining() - 16;

        byte[] token = new byte[length];
        buffer.get(token);

        byte[] integrity = new byte[16];
        buffer.get(integrity);

        assert !buffer.hasRemaining();

        RetryPacket packet = new RetryPacket(packetLength, quicVersion, dstConnectionId, srcConnectionId, token, integrity);

        if (LOG.isDebugEnabled())
            LOG.debug("parsed {}", packet);

        return packet;
    }
}
