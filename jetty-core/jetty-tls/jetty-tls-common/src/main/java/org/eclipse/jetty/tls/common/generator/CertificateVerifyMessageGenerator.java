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

import org.eclipse.jetty.tls.CertificateVerifyMessage;
import org.eclipse.jetty.tls.Message;
import org.eclipse.jetty.util.buffer.BufferAccumulator;
import org.eclipse.jetty.util.buffer.ByteBufferPool;
import org.eclipse.jetty.util.buffer.RetainableByteBuffer;

public class CertificateVerifyMessageGenerator extends MessageGenerator
{
    public CertificateVerifyMessageGenerator(ByteBufferPool byteBufferPool, boolean useDirectBuffers)
    {
        super(byteBufferPool, useDirectBuffers);
    }

    @Override
    public void generate(BufferAccumulator accumulator, Message message) throws Exception
    {
        generate(accumulator, (CertificateVerifyMessage)message);
    }

    private void generate(BufferAccumulator accumulator, CertificateVerifyMessage message) throws Exception
    {
        byte[] signature = message.signature();
        int length = 2 + 2 + signature.length;
        if (length > 0xFFFFFF)
            throw new IllegalStateException("could not generate CertificateVerifyMessage, too long");

        RetainableByteBuffer.Mutable buffer = getByteBufferPool().acquire(4 + length, isUseDirectBuffers());
        accumulator.addRetained(buffer);

        int typeAndLength = (message.type().code() << 24) | length;
        buffer.putInt(typeAndLength);

        buffer.putShort((short)message.signatureAlgorithm().code());
        buffer.putShort((short)signature.length);
        buffer.put(signature);

        notifyMessageGenerated(message);
    }
}
