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

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.tls.CipherSuite;
import org.eclipse.jetty.tls.ClientHello;
import org.eclipse.jetty.tls.Message;

public class ClientHelloGenerator extends MessageGenerator
{
    private final ExtensionsGenerator extensionsGenerator;

    public ClientHelloGenerator(ByteBufferPool byteBufferPool, ExtensionsGenerator extensionsGenerator)
    {
        super(byteBufferPool);
        this.extensionsGenerator = extensionsGenerator;
    }

    @Override
    public void generate(RetainableByteBuffer.Mutable accumulator, Message message)
    {
        generate(accumulator, (ClientHello)message);
    }

    private void generate(RetainableByteBuffer.Mutable accumulator, ClientHello clientHello)
    {
        List<CipherSuite> cipherSuites = clientHello.getCipherSuites();
        int cipherSuitesLength = 2 * cipherSuites.size();

        RetainableByteBuffer.Mutable extensionsAccumulator = new RetainableByteBuffer.DynamicCapacity(getBufferPool(), true, -1, 0, 0);
        int extensionsLength = extensionsGenerator.generate(extensionsAccumulator, clientHello.getExtensions());

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
        int length = 2 + 32 + 1 + 2 + cipherSuitesLength + 1 + 1 + 2 + extensionsLength;
        if (length > 0xFFFFFF)
            throw new IllegalStateException("could not generate ClientHello, too long");

        int typeAndLength = (clientHello.type().type() << 24) | length;
        accumulator.putInt(typeAndLength);

        accumulator.putShort((short)0x0303);

        byte[] random = clientHello.getRandom();
        accumulator.put(random);

        // Legacy session ID.
        accumulator.put((byte)0x00);

        // Cipher suites.
        accumulator.putShort((short)cipherSuitesLength);
        cipherSuites.forEach(c -> accumulator.putShort((short)c.code()));

        // Legacy compression methods (one method, no compression).
        accumulator.put((byte)0x01);
        accumulator.put((byte)0x00);

        accumulator.putShort((short)extensionsLength);
        accumulator.add(extensionsAccumulator);
    }
}
