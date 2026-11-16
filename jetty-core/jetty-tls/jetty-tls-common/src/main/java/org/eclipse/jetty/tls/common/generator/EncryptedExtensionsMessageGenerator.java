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

import org.eclipse.jetty.tls.EncryptedExtensionsMessage;
import org.eclipse.jetty.tls.Message;
import org.eclipse.jetty.util.buffer.BufferAccumulator;
import org.eclipse.jetty.util.buffer.ByteBufferPool;
import org.eclipse.jetty.util.buffer.RetainableByteBuffer;

public class EncryptedExtensionsMessageGenerator extends MessageGenerator
{
    private final ExtensionsGenerator extensionsGenerator;

    public EncryptedExtensionsMessageGenerator(ByteBufferPool byteBufferPool, boolean useDirectBuffers, ExtensionsGenerator extensionsGenerator)
    {
        super(byteBufferPool, useDirectBuffers);
        this.extensionsGenerator = extensionsGenerator;
    }

    @Override
    public void generate(BufferAccumulator accumulator, Message message)
    {
        generate(accumulator, (EncryptedExtensionsMessage)message);
    }

    private void generate(BufferAccumulator accumulator, EncryptedExtensionsMessage message)
    {
        // RFC 8446, 4.3.1.
        int capacity = 4 + 2;
        RetainableByteBuffer.Mutable header = getByteBufferPool().acquire(capacity, isUseDirectBuffers());
        accumulator.addRetained(header);

        int extensionsLength = extensionsGenerator.generate(accumulator, message.extensions());
        if (extensionsLength > 0xFFFF)
            throw new IllegalStateException("could not generate EncryptedExtensions, extensions too long");

        int length = capacity - 4 + extensionsLength;

        int typeAndLength = (message.type().code() << 24) | length;
        header.putInt(typeAndLength);

        header.putShort((short)extensionsLength);

        notifyMessageGenerated(message);
    }
}
