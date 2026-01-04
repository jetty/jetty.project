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

import org.eclipse.jetty.io.ArrayByteBufferPool;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.tls.CipherSuite;
import org.eclipse.jetty.tls.ClientHello;
import org.eclipse.jetty.tls.EncryptedExtensions;
import org.eclipse.jetty.tls.Message;
import org.eclipse.jetty.tls.ServerHello;
import org.eclipse.jetty.tls.TLSVersion;
import org.eclipse.jetty.tls.common.generator.MessagesGenerator;
import org.eclipse.jetty.tls.common.parser.MessagesParser;
import org.eclipse.jetty.tls.ext.SupportedVersionsExtension;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

public class MessagesGenerateParseTest
{
    @Test
    public void testClientHello() throws Exception
    {
        ByteBufferPool byteBufferPool = new ArrayByteBufferPool();
        MessagesGenerator generator = new MessagesGenerator(byteBufferPool);
        RetainableByteBuffer.Mutable accumulator = new RetainableByteBuffer.DynamicCapacity(byteBufferPool, false, -1, 0, 0);
        ClientHello generated = ClientHello.newClientHello();
        generator.generate(accumulator, generated);

        MessagesParser parser = new MessagesParser();
        Message message = parser.parse(accumulator);

        assertInstanceOf(ClientHello.class, message);
        ClientHello parsed = (ClientHello)message;
        assertArrayEquals(generated.getRandom(), parsed.getRandom());
        assertEquals(generated.getCipherSuites(), parsed.getCipherSuites());
        assertEquals(generated.getExtensions(), parsed.getExtensions());

        // Parse again one byte at a time.
        ByteBuffer byteBuffer = accumulator.getByteBuffer().flip();
        while (byteBuffer.hasRemaining())
        {
            int position = byteBuffer.position();
            ByteBuffer oneByteSlice = byteBuffer.slice(position, 1);
            byteBuffer.position(position + 1);
            message = parser.parse(RetainableByteBuffer.wrap(oneByteSlice));
        }

        assertInstanceOf(ClientHello.class, message);
        parsed = (ClientHello)message;
        assertArrayEquals(generated.getRandom(), parsed.getRandom());
        assertEquals(generated.getCipherSuites(), parsed.getCipherSuites());
        assertEquals(generated.getExtensions(), parsed.getExtensions());
    }

    @Test
    public void testServerHello() throws Exception
    {
        ByteBufferPool byteBufferPool = new ArrayByteBufferPool();
        MessagesGenerator generator = new MessagesGenerator(byteBufferPool);
        RetainableByteBuffer.Mutable accumulator = new RetainableByteBuffer.DynamicCapacity(byteBufferPool, false, -1, 0, 0);
        ServerHello generated = new ServerHello();
        generated.setRandom(new byte[32]);
        generated.setSessionId(new byte[32]);
        generated.setCipherSuite(CipherSuite.TLS_AES_128_GCM_SHA256);
        generated.setExtensions(List.of(new SupportedVersionsExtension(List.of(TLSVersion.TLS_1_3))));
        generator.generate(accumulator, generated);

        MessagesParser parser = new MessagesParser();
        Message message = parser.parse(accumulator);

        assertInstanceOf(ServerHello.class, message);
        ServerHello parsed = (ServerHello)message;
        assertArrayEquals(generated.getRandom(), parsed.getRandom());
        assertArrayEquals(generated.getSessionId(), parsed.getSessionId());
        assertEquals(generated.getCipherSuite(), parsed.getCipherSuite());
        assertEquals(generated.getExtensions(), parsed.getExtensions());

        // Parse again one byte at a time.
        ByteBuffer byteBuffer = accumulator.getByteBuffer().flip();
        while (byteBuffer.hasRemaining())
        {
            int position = byteBuffer.position();
            ByteBuffer oneByteSlice = byteBuffer.slice(position, 1);
            byteBuffer.position(position + 1);
            message = parser.parse(RetainableByteBuffer.wrap(oneByteSlice));
        }

        assertInstanceOf(ServerHello.class, message);
        parsed = (ServerHello)message;
        assertArrayEquals(generated.getRandom(), parsed.getRandom());
        assertArrayEquals(generated.getSessionId(), parsed.getSessionId());
        assertEquals(generated.getCipherSuite(), parsed.getCipherSuite());
        assertEquals(generated.getExtensions(), parsed.getExtensions());
    }

    @Test
    public void testEncryptedExtensions() throws Exception
    {
        ByteBufferPool byteBufferPool = new ArrayByteBufferPool();
        MessagesGenerator generator = new MessagesGenerator(byteBufferPool);
        RetainableByteBuffer.Mutable accumulator = new RetainableByteBuffer.DynamicCapacity(byteBufferPool, false, -1, 0, 0);
        EncryptedExtensions generated = new EncryptedExtensions();
        generated.setExtensions(List.of(new SupportedVersionsExtension(List.of(TLSVersion.TLS_1_3))));
        generator.generate(accumulator, generated);

        MessagesParser parser = new MessagesParser();
        Message message = parser.parse(accumulator);

        assertInstanceOf(EncryptedExtensions.class, message);
        EncryptedExtensions parsed = (EncryptedExtensions)message;
        assertEquals(generated.getExtensions(), parsed.getExtensions());

        // Parse again one byte at a time.
        ByteBuffer byteBuffer = accumulator.getByteBuffer().flip();
        while (byteBuffer.hasRemaining())
        {
            int position = byteBuffer.position();
            ByteBuffer oneByteSlice = byteBuffer.slice(position, 1);
            byteBuffer.position(position + 1);
            message = parser.parse(RetainableByteBuffer.wrap(oneByteSlice));
        }

        assertInstanceOf(EncryptedExtensions.class, message);
        parsed = (EncryptedExtensions)message;
        assertEquals(generated.getExtensions(), parsed.getExtensions());
    }
}
