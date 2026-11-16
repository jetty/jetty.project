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

package org.eclipse.jetty.tls.common.generator;

import org.eclipse.jetty.tls.Message;
import org.eclipse.jetty.tls.ServerHelloMessage;
import org.eclipse.jetty.tls.TLSVersion;
import org.eclipse.jetty.util.buffer.BufferAccumulator;
import org.eclipse.jetty.util.buffer.ByteBufferPool;
import org.eclipse.jetty.util.buffer.RetainableByteBuffer;

public class ServerHelloMessageGenerator extends MessageGenerator
{
    private final ExtensionsGenerator extensionsGenerator;

    public ServerHelloMessageGenerator(ByteBufferPool byteBufferPool, boolean useDirectBuffers, ExtensionsGenerator extensionsGenerator)
    {
        super(byteBufferPool, useDirectBuffers);
        this.extensionsGenerator = extensionsGenerator;
    }

    @Override
    public void generate(BufferAccumulator accumulator, Message message)
    {
        generate(accumulator, (ServerHelloMessage)message);
    }

    private void generate(BufferAccumulator accumulator, ServerHelloMessage message)
    {
        // RFC-8446[4.1.3].
        // Field                             | (bytes)
        // ----------------------------------+--------
        // Legacy version                    | (2)
        // Random                            | (32)
        // Legacy session ID length          | (1)
        // Legacy session ID                 | (L)
        // CipherSuite                       | (2)
        // Legacy compression methods Length | (1)
        // Extensions length                 | (2)
        // Extensions                        | (M)

        byte[] sessionId = message.sessionId();
        int capacity = 4 + 2 + 32 + 1 + sessionId.length + 2 + 1 + 2;
        RetainableByteBuffer.Mutable header = getByteBufferPool().acquire(capacity, isUseDirectBuffers());
        accumulator.addRetained(header);

        int extensionsLength = extensionsGenerator.generate(accumulator, message.extensions());
        if (extensionsLength > 0xFFFF)
            throw new IllegalStateException("could not generate ServerHello, extensions too long");

        int length = capacity - 4 + extensionsLength;

        int typeAndLength = (message.type().code() << 24) | length;
        header.putInt(typeAndLength);

        // Legacy version.
        header.putShort((short)TLSVersion.TLS_1_2.code());

        byte[] random = message.random();
        header.put(random);

        // Legacy session ID.
        header.put((byte)sessionId.length);
        header.put(sessionId);

        // Cipher suite.
        header.putShort((short)message.cipherSuite().code());

        // Legacy compression methods (no methods).
        header.put((byte)0x00);

        header.putShort((short)extensionsLength);

        notifyMessageGenerated(message);
    }
}
