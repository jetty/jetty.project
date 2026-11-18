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

import org.eclipse.jetty.quic.common.packets.RetryPacket;
import org.eclipse.jetty.util.buffer.RetainableByteBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RetryPacketGenerator
{
    private static final Logger LOG = LoggerFactory.getLogger(RetryPacketGenerator.class);

    public void generate(RetryPacket packet, RetainableByteBuffer.Mutable packetBuffer)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("generating {}", packet);

        int form = 0b11000000;
        int type = packet.packetType().type(packet.quicVersion()) << 4;
        int firstByte = form | type;
        packetBuffer.put((byte)firstByte);

        packetBuffer.putInt(packet.quicVersion().code());

        byte[] dstConnectionId = packet.destinationConnectionId();
        packetBuffer.put((byte)dstConnectionId.length);
        packetBuffer.put(dstConnectionId);

        byte[] srcConnectionId = packet.sourceConnectionId();
        packetBuffer.put((byte)srcConnectionId.length);
        packetBuffer.put(srcConnectionId);

        // The token length is implicit.
        byte[] token = packet.token();
        packetBuffer.put(token);

        byte[] integrity = packet.integrity();
        if (integrity != null)
            packetBuffer.put(integrity);
    }
}
