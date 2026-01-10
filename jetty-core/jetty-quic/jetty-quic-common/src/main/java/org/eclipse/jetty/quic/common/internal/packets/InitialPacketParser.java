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
import org.eclipse.jetty.quic.api.QuicVersion;
import org.eclipse.jetty.quic.api.frames.Frame;
import org.eclipse.jetty.quic.common.EncryptionLevel;
import org.eclipse.jetty.quic.common.PacketBuffers;
import org.eclipse.jetty.quic.common.frames.FramesParser;
import org.eclipse.jetty.quic.common.internal.Decrypter;
import org.eclipse.jetty.quic.common.packets.InitialPacket;
import org.eclipse.jetty.quic.common.packets.Packet;
import org.eclipse.jetty.quic.common.packets.PacketNumbers;
import org.eclipse.jetty.quic.util.VarLenInt;
import org.eclipse.jetty.util.BufferUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class InitialPacketParser implements PacketParser
{
    private static final Logger LOG = LoggerFactory.getLogger(InitialPacketParser.class);

    private final Decrypter decrypter;
    private final PacketNumbers packetNumbers;
    private final FramesParser framesParser;
    private QuicVersion quicVersion;
    private byte[] dstConnectionId;
    private byte[] srcConnectionId;
    private byte[] token;
    private long packetNumber;

    public InitialPacketParser(Decrypter decrypter, PacketNumbers packetNumbers, FramesParser framesParser)
    {
        this.decrypter = decrypter;
        this.packetNumbers = packetNumbers;
        this.framesParser = framesParser;
    }

    @Override
    public Packet parse(RetainableByteBuffer buffer) throws Exception
    {
        if (LOG.isDebugEnabled())
            LOG.debug("parsing InitialPacket {}", BufferUtil.toDetailString(buffer.getByteBuffer()));

        PacketBuffers packetBuffers = decrypter.decryptLongHeaderPacket(EncryptionLevel.INITIAL, buffer);

        if (LOG.isDebugEnabled())
            LOG.debug("decrypted InitialPacket {}", packetBuffers);

        // TODO: we can introduce a PacketBuffers.Listener to invoke here:
        //  listener.onPacketBuffers(packetBuffers, Promise<Boolean> promise);
        //  The promise boolean indicates whether to continue processing.
        //  In this way, we can write a buffer-level proxy for plaintext QUIC.

        Packet packet = parse(packetBuffers);

        packetBuffers.header().release();
        packetBuffers.payload().release();

        return packet;
    }

    private Packet parse(PacketBuffers packetBuffers)
    {
        parseHeader(packetBuffers.header());
        return parsePayload(packetBuffers.payload());
    }

    private void parseHeader(RetainableByteBuffer header)
    {
        ByteBuffer byteBuffer = header.getByteBuffer();

        if (LOG.isDebugEnabled())
            LOG.debug("parsing InitialPacket header {}", BufferUtil.toDetailString(byteBuffer));

        byte form = byteBuffer.get();
        int encodedPacketNumberLength = (form & 0x03) + 1;

        int versionCode = byteBuffer.getInt();
        quicVersion = QuicVersion.from(versionCode);

        int length = byteBuffer.get();
        dstConnectionId = new byte[length];
        byteBuffer.get(dstConnectionId);

        length = byteBuffer.get();
        srcConnectionId = new byte[length];
        byteBuffer.get(srcConnectionId);

        length = VarLenInt.decodeInt(byteBuffer);
        token = new byte[length];
        byteBuffer.get(token);

        length = VarLenInt.decodeInt(byteBuffer);

        byte[] encodedPacketNumber = new byte[encodedPacketNumberLength];
        byteBuffer.get(encodedPacketNumber);
        packetNumber = packetNumbers.decode(EncryptionLevel.INITIAL, encodedPacketNumber);

        if (LOG.isDebugEnabled())
            LOG.debug("parsed InitialPacket header, version={} packetNumber={} length={}", quicVersion, packetNumber, length);

        assert byteBuffer.remaining() == 0;
    }

    private Packet parsePayload(RetainableByteBuffer payload)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("parsing InitialPacket payload {}", BufferUtil.toDetailString(payload.getByteBuffer()));

        List<Frame> frames = framesParser.consume(payload);
        InitialPacket packet = new InitialPacket(quicVersion, dstConnectionId, srcConnectionId, token, packetNumber, frames);

        if (LOG.isDebugEnabled())
            LOG.debug("parsed {}", packet);

        return packet;
    }
}
