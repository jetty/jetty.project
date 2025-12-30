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

package org.eclipse.jetty.quic.tls.internal;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import org.eclipse.jetty.io.ArrayByteBufferPool;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.quic.api.frames.TransportParameters;
import org.eclipse.jetty.quic.tls.internal.generator.ExtensionsGenerator;
import org.eclipse.jetty.quic.tls.internal.parser.ExtensionsParser;
import org.eclipse.jetty.quic.tls.message.ALPNExtension;
import org.eclipse.jetty.quic.tls.message.Extension;
import org.eclipse.jetty.quic.tls.message.KeyShare;
import org.eclipse.jetty.quic.tls.message.KeyShareExtension;
import org.eclipse.jetty.quic.tls.message.NamedGroup;
import org.eclipse.jetty.quic.tls.message.QuicTransportParametersExtension;
import org.eclipse.jetty.quic.tls.message.ServerNameExtension;
import org.eclipse.jetty.quic.tls.message.SignatureAlgorithm;
import org.eclipse.jetty.quic.tls.message.SignatureAlgorithmsExtension;
import org.eclipse.jetty.quic.tls.message.SupportedGroupsExtension;
import org.eclipse.jetty.quic.tls.message.SupportedVersionsExtension;
import org.eclipse.jetty.quic.tls.message.TLSVersion;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

public class ExtensionsGenerateParseTest
{
    @Test
    public void testGenerateParseALPNExtension()
    {
        ByteBufferPool byteBufferPool = new ArrayByteBufferPool();
        RetainableByteBuffer.Mutable accumulator = new RetainableByteBuffer.DynamicCapacity(byteBufferPool, false, -1, 0, 0);

        ALPNExtension expected = new ALPNExtension(List.of("h2", "http/1.1"));
        ExtensionsGenerator generator = new ExtensionsGenerator();
        int length = generator.generate(accumulator, List.of(expected));

        ExtensionsParser parser = new ExtensionsParser();
        ByteBuffer lengthByteBuffer = ByteBuffer.allocate(2).putShort((short)length).flip();
        assertNull(parser.parse(RetainableByteBuffer.wrap(lengthByteBuffer)));
        List<Extension> extensions = parser.parse(accumulator);
        assertNotNull(extensions);

        assertEquals(1, extensions.size());
        ALPNExtension result = (ALPNExtension)extensions.getFirst();
        assertEquals(expected.protocols(), result.protocols());

        // Parse again one byte at a time.
        parser.parse(RetainableByteBuffer.wrap(lengthByteBuffer.flip()));
        ByteBuffer byteBuffer = accumulator.getByteBuffer().flip();
        while (byteBuffer.hasRemaining())
        {
            int position = byteBuffer.position();
            ByteBuffer oneByteSlice = byteBuffer.slice(position, 1);
            byteBuffer.position(position + 1);
            extensions = parser.parse(RetainableByteBuffer.wrap(oneByteSlice));
        }

        assertNotNull(extensions);
        assertEquals(1, extensions.size());
        result = (ALPNExtension)extensions.getFirst();
        assertEquals(expected.protocols(), result.protocols());
    }

    @Test
    public void testGenerateParseKeyShareExtension()
    {
        ByteBufferPool byteBufferPool = new ArrayByteBufferPool();
        RetainableByteBuffer.Mutable accumulator = new RetainableByteBuffer.DynamicCapacity(byteBufferPool, false, -1, 0, 0);

        byte[] keyExchange1 = new byte[32];
        ThreadLocalRandom.current().nextBytes(keyExchange1);
        KeyShare keyShare1 = new KeyShare(NamedGroup.x25519, keyExchange1);
        byte[] keyExchange2 = new byte[32];
        ThreadLocalRandom.current().nextBytes(keyExchange2);
        KeyShare keyShare2 = new KeyShare(NamedGroup.secp256r1, keyExchange2);
        KeyShareExtension expected = new KeyShareExtension(List.of(keyShare1, keyShare2));
        ExtensionsGenerator generator = new ExtensionsGenerator();
        int length = generator.generate(accumulator, List.of(expected));

        ExtensionsParser parser = new ExtensionsParser();
        ByteBuffer lengthByteBuffer = ByteBuffer.allocate(2).putShort((short)length).flip();
        parser.parse(RetainableByteBuffer.wrap(lengthByteBuffer));
        List<Extension> extensions = parser.parse(accumulator);
        assertNotNull(extensions);

        assertEquals(1, extensions.size());
        KeyShareExtension result = (KeyShareExtension)extensions.getFirst();
        assertEquals(expected.keyShares(), result.keyShares());

        // Parse again one byte at a time.
        parser.parse(RetainableByteBuffer.wrap(lengthByteBuffer.flip()));
        ByteBuffer byteBuffer = accumulator.getByteBuffer().flip();
        while (byteBuffer.hasRemaining())
        {
            int position = byteBuffer.position();
            ByteBuffer oneByteSlice = byteBuffer.slice(position, 1);
            byteBuffer.position(position + 1);
            extensions = parser.parse(RetainableByteBuffer.wrap(oneByteSlice));
        }

        assertNotNull(extensions);
        assertEquals(1, extensions.size());
        result = (KeyShareExtension)extensions.getFirst();
        assertEquals(expected.keyShares(), result.keyShares());
    }

    @Test
    public void testGenerateParseQuicTransportParametersExtension()
    {
        ByteBufferPool byteBufferPool = new ArrayByteBufferPool();
        RetainableByteBuffer.Mutable accumulator = new RetainableByteBuffer.DynamicCapacity(byteBufferPool, false, -1, 0, 0);

        TransportParameters transportParameters = new TransportParameters();
        // Smaller long value.
        transportParameters.put(TransportParameters.Ids.INITIAL_MAX_STREAMS_UNIDIRECTIONAL, 16L);
        // Small long value.
        transportParameters.put(TransportParameters.Ids.INITIAL_MAX_STREAMS_BIDIRECTIONAL, 100L);
        // Large long value.
        transportParameters.put(TransportParameters.Ids.MAX_IDLE_TIMEOUT, 30000L);
        // Larger long value.
        transportParameters.put(TransportParameters.Ids.INITIAL_MAX_DATA, 2147483648L);
        // Larger grease id.
        transportParameters.put(TransportParameters.Ids.create(0xFF02DE1AL, TransportParameters.BytesId::new), new byte[]{13, 7, 19});
        // Unknown id.
        transportParameters.put(TransportParameters.Ids.create(0x5000, TransportParameters.BytesId::new), new byte[]{16, 14, 38});
        QuicTransportParametersExtension expected = new QuicTransportParametersExtension(transportParameters);
        ExtensionsGenerator generator = new ExtensionsGenerator();
        int length = generator.generate(accumulator, List.of(expected));

        ExtensionsParser parser = new ExtensionsParser();
        ByteBuffer lengthByteBuffer = ByteBuffer.allocate(2).putShort((short)length).flip();
        parser.parse(RetainableByteBuffer.wrap(lengthByteBuffer));
        List<Extension> extensions = parser.parse(accumulator);
        assertNotNull(extensions);

        assertEquals(1, extensions.size());
        QuicTransportParametersExtension result = (QuicTransportParametersExtension)extensions.getFirst();
        TransportParameters expectedTransportParameters = expected.parameters();
        TransportParameters resultTransportParameters = result.parameters();
        for (Map.Entry<TransportParameters.Id<?>, Object> entry : expectedTransportParameters)
        {
            switch (entry.getKey())
            {
                case TransportParameters.LongId longId ->
                    assertEquals(expectedTransportParameters.get(longId), resultTransportParameters.get(longId));
                case TransportParameters.BytesId bytesId ->
                    assertArrayEquals(expectedTransportParameters.get(bytesId), resultTransportParameters.get(bytesId));
            }
        }

        // Parse again one byte at a time.
        parser.parse(RetainableByteBuffer.wrap(lengthByteBuffer.flip()));
        ByteBuffer byteBuffer = accumulator.getByteBuffer().flip();
        while (byteBuffer.hasRemaining())
        {
            int position = byteBuffer.position();
            ByteBuffer oneByteSlice = byteBuffer.slice(position, 1);
            byteBuffer.position(position + 1);
            extensions = parser.parse(RetainableByteBuffer.wrap(oneByteSlice));
        }

        assertNotNull(extensions);
        assertEquals(1, extensions.size());
        result = (QuicTransportParametersExtension)extensions.getFirst();
        resultTransportParameters = result.parameters();
        for (Map.Entry<TransportParameters.Id<?>, Object> entry : expectedTransportParameters)
        {
            switch (entry.getKey())
            {
                case TransportParameters.LongId longId ->
                    assertEquals(expectedTransportParameters.get(longId), resultTransportParameters.get(longId));
                case TransportParameters.BytesId bytesId ->
                    assertArrayEquals(expectedTransportParameters.get(bytesId), resultTransportParameters.get(bytesId));
            }
        }
    }

    @Test
    public void testGenerateParseServerNameExtension()
    {
        ByteBufferPool byteBufferPool = new ArrayByteBufferPool();
        RetainableByteBuffer.Mutable accumulator = new RetainableByteBuffer.DynamicCapacity(byteBufferPool, false, -1, 0, 0);

        ServerNameExtension expected = new ServerNameExtension("webtide.com");
        ExtensionsGenerator generator = new ExtensionsGenerator();
        int length = generator.generate(accumulator, List.of(expected));

        ExtensionsParser parser = new ExtensionsParser();
        ByteBuffer lengthByteBuffer = ByteBuffer.allocate(2).putShort((short)length).flip();
        parser.parse(RetainableByteBuffer.wrap(lengthByteBuffer));
        List<Extension> extensions = parser.parse(accumulator);
        assertNotNull(extensions);

        assertEquals(1, extensions.size());
        ServerNameExtension result = (ServerNameExtension)extensions.getFirst();
        assertEquals(expected.serverName(), result.serverName());

        // Parse again one byte at a time.
        parser.parse(RetainableByteBuffer.wrap(lengthByteBuffer.flip()));
        ByteBuffer byteBuffer = accumulator.getByteBuffer().flip();
        while (byteBuffer.hasRemaining())
        {
            int position = byteBuffer.position();
            ByteBuffer oneByteSlice = byteBuffer.slice(position, 1);
            byteBuffer.position(position + 1);
            extensions = parser.parse(RetainableByteBuffer.wrap(oneByteSlice));
        }

        assertNotNull(extensions);
        assertEquals(1, extensions.size());
        result = (ServerNameExtension)extensions.getFirst();
        assertEquals(expected.serverName(), result.serverName());
    }

    @Test
    public void testGenerateParseSignatureAlgorithmsExtension()
    {
        ByteBufferPool byteBufferPool = new ArrayByteBufferPool();
        RetainableByteBuffer.Mutable accumulator = new RetainableByteBuffer.DynamicCapacity(byteBufferPool, false, -1, 0, 0);

        SignatureAlgorithmsExtension expected = new SignatureAlgorithmsExtension(List.of(SignatureAlgorithm.ECDSA_SECP256R1_SHA256, SignatureAlgorithm.RSA_PKCS1_SHA256));
        ExtensionsGenerator generator = new ExtensionsGenerator();
        int length = generator.generate(accumulator, List.of(expected));

        ExtensionsParser parser = new ExtensionsParser();
        ByteBuffer lengthByteBuffer = ByteBuffer.allocate(2).putShort((short)length).flip();
        parser.parse(RetainableByteBuffer.wrap(lengthByteBuffer));
        List<Extension> extensions = parser.parse(accumulator);
        assertNotNull(extensions);

        assertEquals(1, extensions.size());
        SignatureAlgorithmsExtension result = (SignatureAlgorithmsExtension)extensions.getFirst();
        assertEquals(expected.signatureAlgorithms(), result.signatureAlgorithms());

        // Parse again one byte at a time.
        parser.parse(RetainableByteBuffer.wrap(lengthByteBuffer.flip()));
        ByteBuffer byteBuffer = accumulator.getByteBuffer().flip();
        while (byteBuffer.hasRemaining())
        {
            int position = byteBuffer.position();
            ByteBuffer oneByteSlice = byteBuffer.slice(position, 1);
            byteBuffer.position(position + 1);
            extensions = parser.parse(RetainableByteBuffer.wrap(oneByteSlice));
        }

        assertNotNull(extensions);
        assertEquals(1, extensions.size());
        result = (SignatureAlgorithmsExtension)extensions.getFirst();
        assertEquals(expected.signatureAlgorithms(), result.signatureAlgorithms());
    }

    @Test
    public void testGenerateParseSupportedVersionsExtension()
    {
        ByteBufferPool byteBufferPool = new ArrayByteBufferPool();
        RetainableByteBuffer.Mutable accumulator = new RetainableByteBuffer.DynamicCapacity(byteBufferPool, false, -1, 0, 0);

        SupportedVersionsExtension expected = new SupportedVersionsExtension(List.of(TLSVersion.TLS_1_3, TLSVersion.TLS_1_2));
        ExtensionsGenerator generator = new ExtensionsGenerator();
        int length = generator.generate(accumulator, List.of(expected));

        ExtensionsParser parser = new ExtensionsParser();
        ByteBuffer lengthByteBuffer = ByteBuffer.allocate(2).putShort((short)length).flip();
        parser.parse(RetainableByteBuffer.wrap(lengthByteBuffer));
        List<Extension> extensions = parser.parse(accumulator);
        assertNotNull(extensions);

        assertEquals(1, extensions.size());
        SupportedVersionsExtension result = (SupportedVersionsExtension)extensions.getFirst();
        assertEquals(expected.versions(), result.versions());

        // Parse again one byte at a time.
        parser.parse(RetainableByteBuffer.wrap(lengthByteBuffer.flip()));
        ByteBuffer byteBuffer = accumulator.getByteBuffer().flip();
        while (byteBuffer.hasRemaining())
        {
            int position = byteBuffer.position();
            ByteBuffer oneByteSlice = byteBuffer.slice(position, 1);
            byteBuffer.position(position + 1);
            extensions = parser.parse(RetainableByteBuffer.wrap(oneByteSlice));
        }

        assertNotNull(extensions);
        assertEquals(1, extensions.size());
        result = (SupportedVersionsExtension)extensions.getFirst();
        assertEquals(expected.versions(), result.versions());
    }

    @Test
    public void testGenerateParseSupportedGroupsExtension()
    {
        ByteBufferPool byteBufferPool = new ArrayByteBufferPool();
        RetainableByteBuffer.Mutable accumulator = new RetainableByteBuffer.DynamicCapacity(byteBufferPool, false, -1, 0, 0);

        SupportedGroupsExtension expected = new SupportedGroupsExtension(List.of(NamedGroup.secp256r1, NamedGroup.ffdhe2048));
        ExtensionsGenerator generator = new ExtensionsGenerator();
        int length = generator.generate(accumulator, List.of(expected));

        ExtensionsParser parser = new ExtensionsParser();
        ByteBuffer lengthByteBuffer = ByteBuffer.allocate(2).putShort((short)length).flip();
        parser.parse(RetainableByteBuffer.wrap(lengthByteBuffer));
        List<Extension> extensions = parser.parse(accumulator);
        assertNotNull(extensions);

        assertEquals(1, extensions.size());
        SupportedGroupsExtension result = (SupportedGroupsExtension)extensions.getFirst();
        assertEquals(expected.namedGroups(), result.namedGroups());

        // Parse again one byte at a time.
        parser.parse(RetainableByteBuffer.wrap(lengthByteBuffer.flip()));
        ByteBuffer byteBuffer = accumulator.getByteBuffer().flip();
        while (byteBuffer.hasRemaining())
        {
            int position = byteBuffer.position();
            ByteBuffer oneByteSlice = byteBuffer.slice(position, 1);
            byteBuffer.position(position + 1);
            extensions = parser.parse(RetainableByteBuffer.wrap(oneByteSlice));
        }

        assertNotNull(extensions);
        assertEquals(1, extensions.size());
        result = (SupportedGroupsExtension)extensions.getFirst();
        assertEquals(expected.namedGroups(), result.namedGroups());
    }
}
