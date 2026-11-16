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

import org.eclipse.jetty.tls.CertificateRequestMessage;
import org.eclipse.jetty.tls.Message;
import org.eclipse.jetty.util.buffer.BufferAccumulator;
import org.eclipse.jetty.util.buffer.ByteBufferPool;
import org.eclipse.jetty.util.buffer.RetainableByteBuffer;

public class CertificateRequestMessageGenerator extends MessageGenerator
{
    private final ExtensionsGenerator extensionsGenerator;

    protected CertificateRequestMessageGenerator(ByteBufferPool bufferPool, boolean useDirectBuffers, ExtensionsGenerator extensionsGenerator)
    {
        super(bufferPool, useDirectBuffers);
        this.extensionsGenerator = extensionsGenerator;
    }

    @Override
    public void generate(BufferAccumulator accumulator, Message message)
    {
        generate(accumulator, (CertificateRequestMessage)message);
    }

    private void generate(BufferAccumulator accumulator, CertificateRequestMessage message)
    {
        // RFC 8446, 4.3.2.
        // Field                  | (bytes)
        // -----------------------+--------
        // Context length         | (1)
        // Context                | (C)
        // Extensions length      | (2)
        // Extensions             | (M)

        byte[] context = message.context();
        int capacity = 4 + 1 + context.length + 2;
        RetainableByteBuffer.Mutable header = getByteBufferPool().acquire(capacity, isUseDirectBuffers());
        accumulator.addRetained(header);

        int extensionsLength = extensionsGenerator.generate(accumulator, message.extensions());
        if (extensionsLength > 0xFFFF)
            throw new IllegalStateException("could not generate CertificateRequest, extensions too long");

        int length = capacity - 4 + extensionsLength;
        if (length > 0xFFFFFF)
            throw new IllegalStateException("could not generate CertificateRequest, too long");

        int typeAndLength = (message.type().code() << 24) | length;
        header.putInt(typeAndLength);

        header.put((byte)context.length);
        header.put(context);

        header.putShort((short)extensionsLength);

        notifyMessageGenerated(message);
    }
}
