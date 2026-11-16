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

import java.util.List;

import org.eclipse.jetty.tls.CipherSuite;
import org.eclipse.jetty.tls.ClientHelloMessage;
import org.eclipse.jetty.tls.Message;
import org.eclipse.jetty.tls.TLSVersion;
import org.eclipse.jetty.util.buffer.BufferAccumulator;
import org.eclipse.jetty.util.buffer.ByteBufferPool;
import org.eclipse.jetty.util.buffer.RetainableByteBuffer;

public class ClientHelloMessageGenerator extends MessageGenerator
{
    private final ExtensionsGenerator extensionsGenerator;

    public ClientHelloMessageGenerator(ByteBufferPool byteBufferPool, boolean useDirectBuffers, ExtensionsGenerator extensionsGenerator)
    {
        super(byteBufferPool, useDirectBuffers);
        this.extensionsGenerator = extensionsGenerator;
    }

    @Override
    public void generate(BufferAccumulator accumulator, Message message)
    {
        generate(accumulator, (ClientHelloMessage)message);
    }

    private void generate(BufferAccumulator accumulator, ClientHelloMessage message)
    {
        // RFC 8446, 4.1.2.
        // Field                             | (bytes)
        // ----------------------------------+--------
        // Legacy version                    | (2)
        // Random                            | (32)
        // Legacy session ID length          | (1)
        // CipherSuites length               | (2)
        // CipherSuites                      | (N)
        // Legacy compression methods Length | (1)
        // Legacy compression methods        | (1)
        // Extensions length                 | (2)
        // Extensions                        | (M)

        List<CipherSuite> cipherSuites = message.cipherSuites();
        int cipherSuitesLength = 2 * cipherSuites.size();

        int capacity = 4 + 2 + 32 + 1 + 2 + cipherSuitesLength + 1 + 1 + 2;
        RetainableByteBuffer.Mutable header = getByteBufferPool().acquire(capacity, isUseDirectBuffers());
        accumulator.addRetained(header);

        int extensionsLength = extensionsGenerator.generate(accumulator, message.extensions());
        if (extensionsLength > 0xFFFF)
            throw new IllegalStateException("could not generate ClientHello, extensions too long");

        int length = capacity - 4 + extensionsLength;
        if (length > 0xFFFFFF)
            throw new IllegalStateException("could not generate ClientHello, too long");

        int typeAndLength = (message.type().code() << 24) | length;
        header.putInt(typeAndLength);

        // Legacy version.
        header.putShort((short)TLSVersion.TLS_1_2.code());

        byte[] random = message.random();
        header.put(random);

        // Legacy session ID.
        header.put((byte)0x00);

        // Cipher suites.
        header.putShort((short)cipherSuitesLength);
        cipherSuites.forEach(c -> header.putShort((short)c.code()));

        // Legacy compression methods (one method, no compression).
        header.put((byte)0x01);
        header.put((byte)0x00);

        header.putShort((short)extensionsLength);

        notifyMessageGenerated(message);
    }
}
