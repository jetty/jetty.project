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

import org.eclipse.jetty.tls.CertificateMessage;
import org.eclipse.jetty.tls.Message;
import org.eclipse.jetty.util.buffer.BufferAccumulator;
import org.eclipse.jetty.util.buffer.ByteBufferPool;
import org.eclipse.jetty.util.buffer.RetainableByteBuffer;

public class CertificateMessageGenerator extends MessageGenerator
{
    private final ExtensionsGenerator extensionsGenerator;

    public CertificateMessageGenerator(ByteBufferPool byteBufferPool, boolean useDirectBuffers, ExtensionsGenerator extensionsGenerator)
    {
        super(byteBufferPool, useDirectBuffers);
        this.extensionsGenerator = extensionsGenerator;
    }

    @Override
    public void generate(BufferAccumulator accumulator, Message message) throws Exception
    {
        generate(accumulator, (CertificateMessage)message);
    }

    private void generate(BufferAccumulator accumulator, CertificateMessage message) throws Exception
    {
        // RFC 8446, 4.4.2.
        // Field                  | (bytes)
        // -----------------------+--------
        // Context length         | (1)
        // Context                | (C)
        // Entries length         | (3)
        // Entries                | (E)
        //
        // Each entry:
        // Certificate length     | (3)
        // Certificate            | (N)
        // Extensions length      | (2)
        // Extensions             | (M)

        byte[] context = message.requestContext();
        int capacity = 4 + 1 + context.length + 3;
        RetainableByteBuffer.Mutable header = getByteBufferPool().acquire(capacity, isUseDirectBuffers());
        accumulator.addRetained(header);

        int entriesLength = 0;
        for (CertificateMessage.Entry entry : message.entries())
        {
            byte[] certificateBytes = entry.certificate().getEncoded();

            int entryCapacity = 3 + certificateBytes.length + 2;
            RetainableByteBuffer.Mutable entryBuffer = getByteBufferPool().acquire(entryCapacity, isUseDirectBuffers());
            accumulator.addRetained(entryBuffer);

            int extensionsLength = extensionsGenerator.generate(accumulator, entry.extensions());
            if (extensionsLength > 0xFFFF)
                throw new IllegalStateException("could not generate CertificateMessage, extensions too long");

            entryBuffer.put((byte)(certificateBytes.length >> 16));
            entryBuffer.put((byte)(certificateBytes.length >> 8));
            entryBuffer.put((byte)certificateBytes.length);
            entryBuffer.put(certificateBytes);
            entryBuffer.putShort((short)extensionsLength);

            entriesLength += entryCapacity + extensionsLength;
        }

        int length = capacity - 4 + entriesLength;
        if (length > 0xFFFFFF)
            throw new IllegalStateException("could not generate CertificateMessage, too long");

        int typeAndLength = (message.type().code() << 24) | length;
        header.putInt(typeAndLength);

        header.put((byte)context.length);
        header.put(context);

        header.put((byte)(entriesLength >> 16));
        header.put((byte)(entriesLength >> 8));
        header.put((byte)entriesLength);

        notifyMessageGenerated(message);
    }
}
