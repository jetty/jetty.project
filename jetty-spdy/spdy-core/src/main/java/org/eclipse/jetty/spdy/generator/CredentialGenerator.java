/*
 * Copyright (c) 2012 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.eclipse.jetty.spdy.generator;

import java.nio.ByteBuffer;
import java.security.cert.Certificate;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jetty.spdy.ByteBufferPool;
import org.eclipse.jetty.spdy.frames.ControlFrame;
import org.eclipse.jetty.spdy.frames.CredentialFrame;

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
        ByteBuffer buffer = getByteBufferPool().acquire(totalLength, true);
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
        // TODO
        return new ArrayList<>();
    }
}
