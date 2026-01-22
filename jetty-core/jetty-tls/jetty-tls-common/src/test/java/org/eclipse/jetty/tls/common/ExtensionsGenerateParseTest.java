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

package org.eclipse.jetty.tls.common;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import org.eclipse.jetty.io.ArrayByteBufferPool;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.tls.KeyShare;
import org.eclipse.jetty.tls.NamedGroup;
import org.eclipse.jetty.tls.SignatureAlgorithm;
import org.eclipse.jetty.tls.TLSVersion;
import org.eclipse.jetty.tls.common.generator.ExtensionsGenerator;
import org.eclipse.jetty.tls.common.parser.ExtensionsParser;
import org.eclipse.jetty.tls.ext.ALPNExtension;
import org.eclipse.jetty.tls.ext.Extension;
import org.eclipse.jetty.tls.ext.KeyShareExtension;
import org.eclipse.jetty.tls.ext.ServerNameExtension;
import org.eclipse.jetty.tls.ext.SignatureAlgorithmsExtension;
import org.eclipse.jetty.tls.ext.SupportedGroupsExtension;
import org.eclipse.jetty.tls.ext.SupportedVersionsExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

public class ExtensionsGenerateParseTest
{
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void testGenerateParseALPNExtension(boolean client)
    {
        ByteBufferPool byteBufferPool = new ArrayByteBufferPool();
        RetainableByteBuffer.Mutable accumulator = new RetainableByteBuffer.DynamicCapacity(byteBufferPool, false, -1, 0, 0);

        ALPNExtension expected = new ALPNExtension(List.of("h2", "http/1.1"));
        ExtensionsGenerator generator = new ExtensionsGenerator(client);
        int length = generator.generate(accumulator, List.of(expected));
        assertEquals(accumulator.remaining(), length);

        ExtensionsParser parser = new ExtensionsParser(!client);
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

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void testGenerateParseKeyShareExtension(boolean client)
    {
        ByteBufferPool byteBufferPool = new ArrayByteBufferPool();
        RetainableByteBuffer.Mutable accumulator = new RetainableByteBuffer.DynamicCapacity(byteBufferPool, false, -1, 0, 0);

        byte[] keyExchange1 = new byte[32];
        ThreadLocalRandom.current().nextBytes(keyExchange1);
        KeyShare keyShare1 = new KeyShare(NamedGroup.x25519, keyExchange1);
        byte[] keyExchange2 = new byte[32];
        ThreadLocalRandom.current().nextBytes(keyExchange2);
        KeyShare keyShare2 = new KeyShare(NamedGroup.secp256r1, keyExchange2);
        List<KeyShare> keyShares = client ? List.of(keyShare1, keyShare2) : List.of(keyShare1);
        KeyShareExtension expected = new KeyShareExtension(keyShares);
        ExtensionsGenerator generator = new ExtensionsGenerator(client);
        int length = generator.generate(accumulator, List.of(expected));
        assertEquals(accumulator.remaining(), length);

        ExtensionsParser parser = new ExtensionsParser(!client);
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

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void testGenerateParseServerNameExtension(boolean client)
    {
        ByteBufferPool byteBufferPool = new ArrayByteBufferPool();
        RetainableByteBuffer.Mutable accumulator = new RetainableByteBuffer.DynamicCapacity(byteBufferPool, false, -1, 0, 0);

        ServerNameExtension expected = new ServerNameExtension("webtide.com");
        ExtensionsGenerator generator = new ExtensionsGenerator(client);
        int length = generator.generate(accumulator, List.of(expected));
        assertEquals(accumulator.remaining(), length);

        ExtensionsParser parser = new ExtensionsParser(!client);
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

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void testGenerateParseSignatureAlgorithmsExtension(boolean client)
    {
        ByteBufferPool byteBufferPool = new ArrayByteBufferPool();
        RetainableByteBuffer.Mutable accumulator = new RetainableByteBuffer.DynamicCapacity(byteBufferPool, false, -1, 0, 0);

        SignatureAlgorithmsExtension expected = new SignatureAlgorithmsExtension(List.of(SignatureAlgorithm.ECDSA_SECP256R1_SHA256, SignatureAlgorithm.RSA_PSS_RSAE_SHA256));
        ExtensionsGenerator generator = new ExtensionsGenerator(client);
        int length = generator.generate(accumulator, List.of(expected));
        assertEquals(accumulator.remaining(), length);

        ExtensionsParser parser = new ExtensionsParser(!client);
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

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void testGenerateParseSupportedVersionsExtension(boolean client)
    {
        ByteBufferPool byteBufferPool = new ArrayByteBufferPool();
        RetainableByteBuffer.Mutable accumulator = new RetainableByteBuffer.DynamicCapacity(byteBufferPool, false, -1, 0, 0);

        List<TLSVersion> versions = client ? List.of(TLSVersion.TLS_1_3, TLSVersion.TLS_1_2) : List.of(TLSVersion.TLS_1_3);
        SupportedVersionsExtension expected = new SupportedVersionsExtension(versions);
        ExtensionsGenerator generator = new ExtensionsGenerator(client);
        int length = generator.generate(accumulator, List.of(expected));
        assertEquals(accumulator.remaining(), length);

        ExtensionsParser parser = new ExtensionsParser(!client);
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

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void testGenerateParseSupportedGroupsExtension(boolean client)
    {
        ByteBufferPool byteBufferPool = new ArrayByteBufferPool();
        RetainableByteBuffer.Mutable accumulator = new RetainableByteBuffer.DynamicCapacity(byteBufferPool, false, -1, 0, 0);

        SupportedGroupsExtension expected = new SupportedGroupsExtension(List.of(NamedGroup.secp256r1, NamedGroup.ffdhe2048));
        ExtensionsGenerator generator = new ExtensionsGenerator(client);
        int length = generator.generate(accumulator, List.of(expected));
        assertEquals(accumulator.remaining(), length);

        ExtensionsParser parser = new ExtensionsParser(!client);
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
