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

import java.nio.ByteBuffer;

import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.quic.api.QuicVersion;
import org.eclipse.jetty.quic.common.packets.Packet;
import org.eclipse.jetty.quic.common.packets.RetryPacket;
import org.eclipse.jetty.util.BufferUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RetryPacketParser implements PacketParser
{
    private static final Logger LOG = LoggerFactory.getLogger(RetryPacketParser.class);

    @Override
    public Packet parse(RetainableByteBuffer buffer) throws Exception
    {
        if (LOG.isDebugEnabled())
            LOG.debug("parsing RetryPacket {}", BufferUtil.toDetailString(buffer.getByteBuffer()));

        ByteBuffer byteBuffer = buffer.getByteBuffer();

        // Skip the form byte.
        byteBuffer.get();

        int versionCode = byteBuffer.getInt();
        QuicVersion quicVersion = QuicVersion.from(versionCode);

        int length = byteBuffer.get() & 0xFF;
        byte[] dstConnectionId = new byte[length];
        byteBuffer.get(dstConnectionId);

        length = byteBuffer.get() & 0xFF;
        byte[] srcConnectionId = new byte[length];
        byteBuffer.get(srcConnectionId);

        // The token length is implicit, as the integrity is 16 bytes.
        length = buffer.remaining() - 16;

        byte[] token = new byte[length];
        byteBuffer.get(token);

        byte[] integrity = new byte[16];
        byteBuffer.get(integrity);

        assert byteBuffer.remaining() == 0;

        RetryPacket packet = new RetryPacket(quicVersion, dstConnectionId, srcConnectionId, token, integrity);

        if (LOG.isDebugEnabled())
            LOG.debug("parsed {}", packet);

        return packet;
    }
}
