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

import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.quic.common.internal.Encrypter;
import org.eclipse.jetty.quic.common.packets.Packet;
import org.eclipse.jetty.quic.common.packets.RetryPacket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RetryPacketGenerator implements PacketGenerator
{
    private static final Logger LOG = LoggerFactory.getLogger(RetryPacketGenerator.class);

    private final Encrypter encrypter;

    public RetryPacketGenerator(Encrypter encrypter)
    {
        this.encrypter = encrypter;
    }

    @Override
    public void generate(RetainableByteBuffer.Mutable accumulator, Packet packet) throws Exception
    {
        generate(accumulator, (RetryPacket)packet);
    }

    private void generate(RetainableByteBuffer.Mutable accumulator, RetryPacket packet)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("generating {}", packet);

        int form = 0b11000000;
        int type = packet.packetType().type(packet.quicVersion()) << 4;
        int msb = form | type;
        accumulator.put((byte)msb);

        accumulator.putInt(packet.quicVersion().code());

        byte[] dstConnectionId = packet.destinationConnectionId();
        accumulator.put((byte)dstConnectionId.length);
        accumulator.put(dstConnectionId);

        byte[] srcConnectionId = packet.sourceConnectionId();
        accumulator.put((byte)srcConnectionId.length);
        accumulator.put(srcConnectionId);

        // The token length is implicit.
        byte[] token = packet.token();
        accumulator.put(token);

        // TODO
//        byte[] integrity = encrypter.generateRetryIntegrity(retryAccumulator);
//        accumulator.put(integrity);
    }
}
