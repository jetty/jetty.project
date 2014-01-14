//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.spdy.generator;

import java.nio.ByteBuffer;
import java.security.cert.Certificate;
import java.security.cert.CertificateEncodingException;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.spdy.SessionException;
import org.eclipse.jetty.spdy.api.SessionStatus;
import org.eclipse.jetty.spdy.frames.ControlFrame;
import org.eclipse.jetty.spdy.frames.CredentialFrame;
import org.eclipse.jetty.util.BufferUtil;

public class CredentialGenerator extends ControlFrameGenerator
{
    public CredentialGenerator(ByteBufferPool bufferPool)
    {
        super(bufferPool);
    }

    @Override
    public ByteBuffer generate(ControlFrame frame)
    {
        CredentialFrame credential = (CredentialFrame)frame;

        byte[] proof = credential.getProof();

        List<byte[]> certificates = serializeCertificates(credential.getCertificateChain());
        int certificatesLength = 0;
        for (byte[] certificate : certificates)
            certificatesLength += certificate.length;

        int frameBodyLength = 2 + 4 + proof.length + certificates.size() * 4 + certificatesLength;

        int totalLength = ControlFrame.HEADER_LENGTH + frameBodyLength;
        ByteBuffer buffer = getByteBufferPool().acquire(totalLength, Generator.useDirectBuffers);
        BufferUtil.clearToFill(buffer);
        generateControlFrameHeader(credential, frameBodyLength, buffer);

        buffer.putShort(credential.getSlot());
        buffer.putInt(proof.length);
        buffer.put(proof);
        for (byte[] certificate : certificates)
        {
            buffer.putInt(certificate.length);
            buffer.put(certificate);
        }

        buffer.flip();
        return buffer;
    }

    private List<byte[]> serializeCertificates(Certificate[] certificates)
    {
        try
        {
            List<byte[]> result = new ArrayList<>(certificates.length);
            for (Certificate certificate : certificates)
                result.add(certificate.getEncoded());
            return result;
        }
        catch (CertificateEncodingException x)
        {
            throw new SessionException(SessionStatus.PROTOCOL_ERROR, x);
        }
    }
}
