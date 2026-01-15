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

package org.eclipse.jetty.tls.common.parser;

import java.nio.ByteBuffer;

import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.tls.CertificateVerifyMessage;
import org.eclipse.jetty.tls.Message;
import org.eclipse.jetty.tls.SignatureAlgorithm;

public class CertificateVerifyMessageParser implements MessageParser
{
    private State state = State.ALGORITHM;
    private int cursor;
    private int algorithm;
    private int signatureLength;
    private byte[] signature;

    @Override
    public Message parse(int messageLength, RetainableByteBuffer buffer) throws Exception
    {
        while (true)
        {
            ByteBuffer byteBuffer = buffer.getByteBuffer();
            int remaining = byteBuffer.remaining();
            if (remaining == 0)
                return null;
            switch (state)
            {
                case ALGORITHM ->
                {
                    if (remaining > 2)
                    {
                        algorithm = byteBuffer.getShort() & 0xFFFF;
                        state = State.SIGNATURE_LENGTH;
                    }
                    else
                    {
                        cursor = 2;
                        state = State.ALGORITHM_BYTES;
                    }
                }
                case ALGORITHM_BYTES ->
                {
                    int b = byteBuffer.get() & 0xFF;
                    --cursor;
                    algorithm += b << (8 * cursor);
                    if (cursor == 0)
                        state = State.SIGNATURE_LENGTH;
                }
                case SIGNATURE_LENGTH ->
                {
                    if (remaining > 2)
                    {
                        signatureLength = byteBuffer.getShort() & 0xFFFF;
                        signature = new byte[signatureLength];
                        state = State.SIGNATURE;
                    }
                    else
                    {
                        cursor = 2;
                        state = State.SIGNATURE_LENGTH_BYTES;
                    }
                }
                case SIGNATURE_LENGTH_BYTES ->
                {
                    int b = byteBuffer.get() & 0xFF;
                    --cursor;
                    signatureLength += b << (8 * cursor);
                    if (cursor == 0)
                    {
                        signature = new byte[signatureLength];
                        state = State.SIGNATURE;
                    }
                }
                case SIGNATURE ->
                {
                    int offset = cursor;
                    int length = Math.min(signatureLength - cursor, remaining);
                    byteBuffer.get(signature, offset, length);
                    cursor += length;
                    if (cursor == signatureLength)
                    {
                        SignatureAlgorithm signatureAlgorithm = SignatureAlgorithm.from(algorithm);
                        if (signatureAlgorithm == null)
                            throw new IllegalArgumentException("unknown signature algorithm " + Integer.toHexString(algorithm));
                        CertificateVerifyMessage message = new CertificateVerifyMessage(signatureAlgorithm, signature);
                        state = State.ALGORITHM;
                        cursor = 0;
                        algorithm = 0;
                        signatureLength = 0;
                        signature = null;
                        return message;
                    }
                }
            }
        }
    }

    private enum State
    {
        ALGORITHM,
        ALGORITHM_BYTES,
        SIGNATURE_LENGTH,
        SIGNATURE_LENGTH_BYTES,
        SIGNATURE
    }
}
