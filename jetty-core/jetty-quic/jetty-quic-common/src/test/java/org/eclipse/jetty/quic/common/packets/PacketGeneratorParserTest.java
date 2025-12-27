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

package org.eclipse.jetty.quic.common.packets;

import java.nio.ByteBuffer;
import java.util.List;

import org.eclipse.jetty.io.ArrayByteBufferPool;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.quic.api.Version;
import org.eclipse.jetty.quic.api.frames.CryptoFrame;
import org.eclipse.jetty.quic.common.frames.FrameGenerator;
import org.eclipse.jetty.quic.common.internal.TLSEngine;
import org.eclipse.jetty.quic.common.internal.packets.InitialPacketGenerator;
import org.eclipse.jetty.quic.common.internal.packets.PacketGenerator;
import org.eclipse.jetty.quic.common.internal.packets.PacketNumbers;
import org.eclipse.jetty.util.StringUtil;
import org.junit.jupiter.api.Test;

public class PacketGeneratorParserTest
{
    @Test
    public void testInitialPacket() throws Exception
    {
        // ClientHello clientHello = new ClientHello();
        byte[] clientHello = StringUtil.fromHexString(("       010000ed0303ebf8fa56f129 39b9584a3896472ec40bb863cfd3e868" +
                                                      "04fe3a47f06a2b69484c000004130113 02010000c000000010000e00000b6578" +
                                                      "616d706c652e636f6dff01000100000a 00080006001d00170018001000070005" +
                                                      "04616c706e0005000501000000000033 00260024001d00209370b2c9caa47fba" +
                                                      "baf4559fedba753de171fa71f50f1ce1 5d43e994ec74d748002b000302030400" +
                                                      "0d0010000e0403050306030203080408 050806002d00020101001c0002400100" +
                                                      "3900320408ffffffffffffffff050480 00ffff07048000ffff08011001048000" +
                                                      "75300901100f088394c8f03e51570806 048000ffff").replaceAll(" ", ""));
        CryptoFrame cryptoFrame = new CryptoFrame(0, ByteBuffer.wrap(clientHello));
        byte[] source = new byte[0];
        byte[] destination = StringUtil.fromHexString("8394c8f03e515708");
        byte[] token = new byte[0];
        InitialPacket initialPacket = new InitialPacket(Version.V1, source, destination, token, 2, List.of(cryptoFrame));

        ByteBufferPool byteBufferPool = new ArrayByteBufferPool();
        PacketNumbers packetNumbers = new PacketNumbers();
        FrameGenerator frameGenerator = new FrameGenerator(byteBufferPool);
        TLSEngine tlsEngine = new TLSEngine(true);
        tlsEngine.allocateInitialKeys(Version.V1, destination);
        PacketGenerator generator = new InitialPacketGenerator(byteBufferPool, packetNumbers, frameGenerator, tlsEngine);
        RetainableByteBuffer.Mutable accumulator = new RetainableByteBuffer.DynamicCapacity(byteBufferPool, true, -1, 0, 0);
        generator.generate(accumulator, initialPacket);

        ByteBuffer byteBuffer = accumulator.getByteBuffer();
        byte[] bytes = new byte[byteBuffer.remaining()];
        byteBuffer.get(bytes);
        System.err.println(StringUtil.toHexString(bytes));
    }
}
