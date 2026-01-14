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
import org.eclipse.jetty.tls.EncryptedExtensionsMessage;
import org.eclipse.jetty.tls.Message;

public class EncryptedExtensionsMessageGenerator extends MessageGenerator
{
    private final ExtensionsGenerator extensionsGenerator;

    public EncryptedExtensionsMessageGenerator(ByteBufferPool byteBufferPool, ExtensionsGenerator extensionsGenerator)
    {
        super(byteBufferPool);
        this.extensionsGenerator = extensionsGenerator;
    }

    @Override
    public void generate(RetainableByteBuffer.Mutable accumulator, Message message)
    {
        generate(accumulator, (EncryptedExtensionsMessage)message);
    }

    private void generate(RetainableByteBuffer.Mutable accumulator, EncryptedExtensionsMessage message)
    {
        RetainableByteBuffer.Mutable extensionsAccumulator = new RetainableByteBuffer.DynamicCapacity(getBufferPool(), true, -1, 0, 0);
        int extensionsLength = extensionsGenerator.generate(extensionsAccumulator, message.extensions());
        if (extensionsLength > 0xFFFF)
            throw new IllegalStateException("could not generate EncryptedExtensions, extensions too long");

        // RFC 8446, 4.3.1.
        int length = 2 + extensionsLength;

        int typeAndLength = (message.type().type() << 24) | length;
        accumulator.putInt(typeAndLength);

        accumulator.putShort((short)extensionsLength);
        accumulator.add(extensionsAccumulator);

        notifyMessageGenerated(message);
    }
}
