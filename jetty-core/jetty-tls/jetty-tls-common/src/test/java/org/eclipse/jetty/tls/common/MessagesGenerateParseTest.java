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

import org.eclipse.jetty.io.ArrayByteBufferPool;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.tls.ClientHello;
import org.eclipse.jetty.tls.Message;
import org.eclipse.jetty.tls.common.generator.MessagesGenerator;
import org.eclipse.jetty.tls.common.parser.MessagesParser;
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
        ClientHello clientHello = ClientHello.newClientHello();
        generator.generate(accumulator, clientHello);

        MessagesParser parser = new MessagesParser();
        Message message = parser.parse(accumulator);

        assertInstanceOf(ClientHello.class, message);
        ClientHello result = (ClientHello)message;
        assertArrayEquals(clientHello.getRandom(), result.getRandom());
        assertEquals(clientHello.getCipherSuites(), result.getCipherSuites());
        assertEquals(clientHello.getExtensions(), result.getExtensions());

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
        result = (ClientHello)message;
        assertArrayEquals(clientHello.getRandom(), result.getRandom());
        assertEquals(clientHello.getCipherSuites(), result.getCipherSuites());
        assertEquals(clientHello.getExtensions(), result.getExtensions());
    }
}
