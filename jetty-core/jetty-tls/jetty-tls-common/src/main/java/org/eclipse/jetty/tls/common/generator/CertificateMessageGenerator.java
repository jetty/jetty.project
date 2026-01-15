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

import java.util.ArrayList;
import java.util.List;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.tls.CertificateMessage;
import org.eclipse.jetty.tls.Message;

public class CertificateMessageGenerator extends MessageGenerator
{
    private final ExtensionsGenerator extensionsGenerator;

    public CertificateMessageGenerator(ByteBufferPool byteBufferPool, ExtensionsGenerator extensionsGenerator)
    {
        super(byteBufferPool);
        this.extensionsGenerator = extensionsGenerator;
    }

    @Override
    public void generate(RetainableByteBuffer.Mutable accumulator, Message message) throws Exception
    {
        generate(accumulator, (CertificateMessage)message);
    }

    private void generate(RetainableByteBuffer.Mutable accumulator, CertificateMessage message) throws Exception
    {
        byte[] context = message.context();
        List<CertificateMessage.Entry> entries = message.entries();

        // RFC 8446, 4.4.2.
        int entriesLength = 0;
        List<byte[]> encodedCertificates = new ArrayList<>();
        List<RetainableByteBuffer> encodedExtensions = new ArrayList<>();
        for (CertificateMessage.Entry entry : entries)
        {
            // The certificate length is encoded in 3 bytes.
            entriesLength += 3;

            byte[] certificateBytes = entry.certificate().getEncoded();
            encodedCertificates.add(certificateBytes);
            entriesLength += certificateBytes.length;

            // The extensions length is encoded in 2 bytes.
            entriesLength += 2;

            RetainableByteBuffer.Mutable extensionsAccumulator = new RetainableByteBuffer.DynamicCapacity(getBufferPool(), true, -1, 0, 0);
            encodedExtensions.add(extensionsAccumulator);
            int extensionsLength = extensionsGenerator.generate(extensionsAccumulator, entry.extensions());
            if (extensionsLength > 0xFFFF)
                throw new IllegalStateException("could not generate CertificateMessage, extensions too long");
            entriesLength += extensionsLength;
        }

        int length = 1 + context.length + 3 + entriesLength;
        if (length > 0xFFFFFF)
            throw new IllegalStateException("could not generate CertificateMessage, too long");

        int typeAndLength = (message.type().type() << 24) | length;
        accumulator.putInt(typeAndLength);

        accumulator.put((byte)context.length);
        accumulator.put(context);

        accumulator.put((byte)(entriesLength >> 16));
        accumulator.put((byte)(entriesLength >> 8));
        accumulator.put((byte)entriesLength);

        for (int i = 0; i < entries.size(); ++i)
        {
            byte[] certificateBytes = encodedCertificates.get(i);
            accumulator.put((byte)(certificateBytes.length >> 16));
            accumulator.put((byte)(certificateBytes.length >> 8));
            accumulator.put((byte)certificateBytes.length);
            accumulator.put(certificateBytes);

            RetainableByteBuffer extensionsAccumulator = encodedExtensions.get(i);
            accumulator.putShort((short)extensionsAccumulator.remaining());
            accumulator.add(extensionsAccumulator);
        }

        notifyMessageGenerated(message);
    }
}
