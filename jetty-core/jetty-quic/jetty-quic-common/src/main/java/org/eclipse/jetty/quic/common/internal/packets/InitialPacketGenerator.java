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

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.quic.common.frames.FrameGenerator;
import org.eclipse.jetty.quic.common.internal.Encrypter;
import org.eclipse.jetty.quic.common.internal.EncryptionLevel;
import org.eclipse.jetty.quic.common.packets.InitialPacket;
import org.eclipse.jetty.quic.common.packets.Packet;
import org.eclipse.jetty.quic.util.VarLenInt;

public class InitialPacketGenerator implements PacketGenerator
{
    private final ByteBufferPool byteBufferPool;
    private final PacketNumbers packetNumbers;
    private final FrameGenerator frameGenerator;
    private final Encrypter encrypter;

    public InitialPacketGenerator(ByteBufferPool byteBufferPool, PacketNumbers packetNumbers, FrameGenerator frameGenerator, Encrypter encrypter)
    {
        this.byteBufferPool = byteBufferPool;
        this.packetNumbers = packetNumbers;
        this.frameGenerator = frameGenerator;
        this.encrypter = encrypter;
    }

    @Override
    public void generate(RetainableByteBuffer.Mutable accumulator, Packet packet) throws Exception
    {
        generate(accumulator, (InitialPacket)packet);
    }

    private void generate(RetainableByteBuffer.Mutable accumulator, InitialPacket packet) throws Exception
    {
        // TODO: fix these deprecations.
        ByteBufferPool.Accumulator frameAccumulator = new ByteBufferPool.Accumulator();
        packet.getFrames().forEach(frame -> frameGenerator.generate(frameAccumulator, frame));
        RetainableByteBuffer.Mutable encodedFrames = new RetainableByteBuffer.DynamicCapacity(null, true, -1, 0, 0);
        frameAccumulator.getByteBuffers().forEach(encodedFrames::add);

        long framesLength = encodedFrames.size();
        if (framesLength < 1200)
        {
            // A PADDING frame is just the byte 0x00.
            byte[] padding = new byte[Math.toIntExact(1200 - framesLength)];
            encodedFrames.put(padding);
        }

        RetainableByteBuffer.Mutable headerAccumulator = new RetainableByteBuffer.DynamicCapacity(null, true, -1, 0, 0);
        int form = 0b11000000;
        int type = packet.getPacketType().type(packet.getVersion()) << 4;
        PacketNumber packetNumber = packetNumbers.newPacketNumber(packet.getPacketNumber());
        int msb = form | type | (packetNumber.encodedPacketNumberLength() - 1);
        headerAccumulator.put((byte)msb);

        headerAccumulator.putInt(packet.getVersion().code());

        byte[] dstConnectionId = packet.getDestinationConnectionId();
        headerAccumulator.put((byte)dstConnectionId.length);
        headerAccumulator.put(dstConnectionId);

        byte[] srcConnectionId = packet.getSourceConnectionId();
        headerAccumulator.put((byte)srcConnectionId.length);
        headerAccumulator.put(srcConnectionId);

        byte[] token = packet.getToken();
        VarLenInt.encode(headerAccumulator, token.length);
        headerAccumulator.put(token);

        // AEAD encryption produces 16 additional bytes.
        long encryptedFramesLength = encodedFrames.size() + 16;
        long packetLength = packetNumber.encodedPacketNumberLength() + encryptedFramesLength;

        VarLenInt.encode(headerAccumulator, packetLength);

        packetNumber.putTo(headerAccumulator);

        RetainableByteBuffer.Mutable encryptedHeader = byteBufferPool.acquire((int)headerAccumulator.size(), true);
        encryptedHeader.getByteBuffer().clear();
        RetainableByteBuffer.Mutable encryptedPayload = byteBufferPool.acquire((int)encryptedFramesLength, true);
        encryptedPayload.getByteBuffer().clear();
        encrypter.encrypt(EncryptionLevel.INITIAL, packetNumber,
            headerAccumulator.getByteBuffer(), encryptedHeader.getByteBuffer(),
            encodedFrames.getByteBuffer(), encryptedPayload.getByteBuffer());

        accumulator.add(encryptedHeader);
        accumulator.add(encryptedPayload);
    }
}
