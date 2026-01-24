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
import org.eclipse.jetty.tls.CertificateVerifyMessage;
import org.eclipse.jetty.tls.Message;

public class CertificateVerifyMessageGenerator extends MessageGenerator
{
    public CertificateVerifyMessageGenerator(ByteBufferPool byteBufferPool)
    {
        super(byteBufferPool);
    }

    @Override
    public void generate(RetainableByteBuffer.Mutable accumulator, Message message) throws Exception
    {
        generate(accumulator, (CertificateVerifyMessage)message);
    }

    private void generate(RetainableByteBuffer.Mutable accumulator, CertificateVerifyMessage message) throws Exception
    {
        byte[] signature = message.signature();

        int length = 2 + 2 + signature.length;
        if (length > 0xFFFFFF)
            throw new IllegalStateException("could not generate CertificateVerifyMessage, too long");

        int typeAndLength = (message.type().code() << 24) | length;
        accumulator.putInt(typeAndLength);

        accumulator.putShort((short)message.algorithm().code());
        accumulator.putShort((short)signature.length);
        accumulator.put(signature);

        notifyMessageGenerated(message);
    }
}
