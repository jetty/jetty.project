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
import java.util.List;

import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.quic.api.frames.Frame;
import org.eclipse.jetty.quic.common.EncryptionLevel;
import org.eclipse.jetty.quic.common.PacketBuffers;
import org.eclipse.jetty.quic.common.frames.FramesParser;
import org.eclipse.jetty.quic.common.internal.Decrypter;
import org.eclipse.jetty.quic.common.packets.OneRTTPacket;
import org.eclipse.jetty.quic.common.packets.Packet;
import org.eclipse.jetty.quic.common.packets.PacketNumbers;
import org.eclipse.jetty.util.BufferUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class OneRTTPacketParser implements PacketParser
{
    private static final Logger LOG = LoggerFactory.getLogger(OneRTTPacketParser.class);

    private final Decrypter decrypter;
    private final PacketNumbers packetNumbers;
    private final FramesParser framesParser;
    private byte[] dstConnectionId;

    public OneRTTPacketParser(Decrypter decrypter, PacketNumbers packetNumbers, FramesParser framesParser)
    {
        this.decrypter = decrypter;
        this.packetNumbers = packetNumbers;
        this.framesParser = framesParser;
    }

    public void setDestinationConnectionId(byte[] dstConnectionId)
    {
        this.dstConnectionId = dstConnectionId;
    }

    @Override
    public Packet parse(RetainableByteBuffer buffer) throws Exception
    {
        if (LOG.isDebugEnabled())
            LOG.debug("parsing OneRTTPacket {}", BufferUtil.toDetailString(buffer.getByteBuffer()));

        PacketBuffers packetBuffers = decrypter.decryptShortHeaderPacket(dstConnectionId, buffer);

        if (LOG.isDebugEnabled())
            LOG.debug("decrypted OneRTTPacket {}", packetBuffers);

        if (packetBuffers == null)
        {
            buffer.skip(buffer.size());
            return Packet.DISCARD;
        }

        // TODO: we can introduce a PacketBuffers.Listener to invoke here:
        //  listener.onPacketBuffers(packetBuffers, Promise<Boolean> promise);
        //  The promise boolean indicates whether to continue processing.
        //  In this way, we can write a buffer-level proxy for plaintext QUIC.

        RetainableByteBuffer header = packetBuffers.header();
        ByteBuffer byteBuffer = header.getByteBuffer();
        if (LOG.isDebugEnabled())
            LOG.debug("parsing OneRTTPacket header {}", BufferUtil.toDetailString(byteBuffer));

        byte form = byteBuffer.get();
        boolean spin = (form & 0b00100000) != 0;
        boolean keyPhase = (form & 0b00000100) != 0;
        int encodedPacketNumberLength = (form & 0b0000011) + 1;

        byte[] dcid = new byte[dstConnectionId.length];
        byteBuffer.get(dcid);

        byte[] encodedPacketNumber = new byte[encodedPacketNumberLength];
        byteBuffer.get(encodedPacketNumber);
        long packetNumber = packetNumbers.decode(EncryptionLevel.ONE_RTT, encodedPacketNumber);

        assert byteBuffer.remaining() == 0;
        header.release();

        if (LOG.isDebugEnabled())
            LOG.debug("parsed OneRTTPacket header, packetNumber={}", packetNumber);

        RetainableByteBuffer payload = packetBuffers.payload();
        if (LOG.isDebugEnabled())
            LOG.debug("parsing OneRTTPacket payload {}", BufferUtil.toDetailString(payload.getByteBuffer()));

        List<Frame> frames = framesParser.consume(payload);
        payload.release();

        OneRTTPacket packet = new OneRTTPacket(packetNumber, dcid, spin, keyPhase, frames);

        if (LOG.isDebugEnabled())
            LOG.debug("parsed {}", packet);

        return packet;
    }
}
