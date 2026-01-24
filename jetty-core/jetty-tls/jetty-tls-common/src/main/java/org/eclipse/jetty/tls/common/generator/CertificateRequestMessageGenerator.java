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
import org.eclipse.jetty.tls.CertificateRequestMessage;
import org.eclipse.jetty.tls.Message;

public class CertificateRequestMessageGenerator extends MessageGenerator
{
    private final ExtensionsGenerator extensionsGenerator;

    protected CertificateRequestMessageGenerator(ByteBufferPool bufferPool, ExtensionsGenerator extensionsGenerator)
    {
        super(bufferPool);
        this.extensionsGenerator = extensionsGenerator;
    }

    @Override
    public void generate(RetainableByteBuffer.Mutable accumulator, Message message)
    {
        generate(accumulator, (CertificateRequestMessage)message);
    }

    private void generate(RetainableByteBuffer.Mutable accumulator, CertificateRequestMessage message)
    {
        byte[] context = message.context();

        RetainableByteBuffer.Mutable extensionsAccumulator = new RetainableByteBuffer.DynamicCapacity(getBufferPool(), true, -1, 0, 0);
        int extensionsLength = extensionsGenerator.generate(extensionsAccumulator, message.extensions());
        if (extensionsLength > 0xFFFF)
            throw new IllegalStateException("could not generate ClientHello, extensions too long");

        // RFC 8446, 4.3.2.
        int length = 1 + context.length + 2 + extensionsLength;

        int typeAndLength = (message.type().code() << 24) | length;
        accumulator.putInt(typeAndLength);

        accumulator.put((byte)context.length);
        accumulator.put(context);

        accumulator.putShort((short)extensionsLength);
        accumulator.add(extensionsAccumulator);

        notifyMessageGenerated(message);
    }
}
