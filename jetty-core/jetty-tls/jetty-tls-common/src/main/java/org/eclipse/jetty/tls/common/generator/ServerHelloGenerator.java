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

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.tls.Message;
import org.eclipse.jetty.tls.ServerHello;

public class ServerHelloGenerator extends MessageGenerator
{
    private final ExtensionsGenerator extensionsGenerator;

    public ServerHelloGenerator(ByteBufferPool byteBufferPool, ExtensionsGenerator extensionsGenerator)
    {
        super(byteBufferPool);
        this.extensionsGenerator = extensionsGenerator;
    }

    @Override
    public void generate(RetainableByteBuffer.Mutable accumulator, Message message)
    {
        generate(accumulator, (ServerHello)message);
    }

    private void generate(RetainableByteBuffer.Mutable accumulator, ServerHello serverHello)
    {
        byte[] sessionId = serverHello.getSessionId();

        RetainableByteBuffer.Mutable extensionsAccumulator = new RetainableByteBuffer.DynamicCapacity(getBufferPool(), true, -1, 0, 0);
        int extensionsLength = extensionsGenerator.generate(extensionsAccumulator, serverHello.getExtensions());
        if (extensionsLength > 0xFFFF)
            throw new IllegalStateException("could not generate ServerHello, extensions too long");

        // RFC 8446, 4.1.3.
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
        int length = 2 + 32 + 1 + sessionId.length + 2 + 1 + 2 + extensionsLength;
        if (length > 0xFFFFFF)
            throw new IllegalStateException("could not generate ServerHello, too long");

        int typeAndLength = (serverHello.type().type() << 24) | length;
        accumulator.putInt(typeAndLength);

        accumulator.putShort((short)0x0303);

        byte[] random = serverHello.getRandom();
        accumulator.put(random);

        // Legacy session ID.
        accumulator.put((byte)sessionId.length);
        accumulator.put(sessionId);

        // Cipher suite.
        accumulator.putShort((short)serverHello.getCipherSuite().code());

        // Legacy compression methods (no methods).
        accumulator.put((byte)0x00);

        accumulator.putShort((short)extensionsLength);
        accumulator.add(extensionsAccumulator);
    }
}
