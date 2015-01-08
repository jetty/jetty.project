//
//  ========================================================================
//  Copyright (c) 1995-2015 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.spdy.parser;

import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.eclipse.jetty.spdy.SessionException;
import org.eclipse.jetty.spdy.api.SessionStatus;
import org.eclipse.jetty.spdy.frames.ControlFrameType;
import org.eclipse.jetty.spdy.frames.CredentialFrame;

public class CredentialBodyParser extends ControlFrameBodyParser
{
    private final List<Certificate> certificates = new ArrayList<>();
    private final ControlFrameParser controlFrameParser;
    private State state = State.SLOT;
    private int totalLength;
    private int cursor;
    private short slot;
    private int proofLength;
    private byte[] proof;
    private int certificateLength;
    private byte[] certificate;

    public CredentialBodyParser(ControlFrameParser controlFrameParser)
    {
        this.controlFrameParser = controlFrameParser;
    }

    @Override
    public boolean parse(ByteBuffer buffer)
    {
        while (buffer.hasRemaining())
        {
            switch (state)
            {
                case SLOT:
                {
                    if (buffer.remaining() >= 2)
                    {
                        slot = buffer.getShort();
                        checkSlotValid();
                        state = State.PROOF_LENGTH;
                    }
                    else
                    {
                        state = State.SLOT_BYTES;
                        cursor = 2;
                    }
                    break;
                }
                case SLOT_BYTES:
                {
                    byte currByte = buffer.get();
                    --cursor;
                    slot += (currByte & 0xFF) << 8 * cursor;
                    if (cursor == 0)
                    {
                        checkSlotValid();
                        state = State.PROOF_LENGTH;
                    }
                    break;
                }
                case PROOF_LENGTH:
                {
                    if (buffer.remaining() >= 4)
                    {
                        proofLength = buffer.getInt() & 0x7F_FF_FF_FF;
                        state = State.PROOF;
                    }
                    else
                    {
                        state = State.PROOF_LENGTH_BYTES;
                        cursor = 4;
                    }
                    break;
                }
                case PROOF_LENGTH_BYTES:
                {
                    byte currByte = buffer.get();
                    --cursor;
                    proofLength += (currByte & 0xFF) << 8 * cursor;
                    if (cursor == 0)
                    {
                        proofLength &= 0x7F_FF_FF_FF;
                        state = State.PROOF;
                    }
                    break;
                }
                case PROOF:
                {
                    totalLength = controlFrameParser.getLength() - 2 - 4 - proofLength;
                    proof = new byte[proofLength];
                    if (buffer.remaining() >= proofLength)
                    {
                        buffer.get(proof);
                        state = State.CERTIFICATE_LENGTH;
                        if (totalLength == 0)
                        {
                            onCredential();
                            return true;
                        }
                    }
                    else
                    {
                        state = State.PROOF_BYTES;
                        cursor = proofLength;
                    }
                    break;
                }
                case PROOF_BYTES:
                {
                    proof[proofLength - cursor] = buffer.get();
                    --cursor;
                    if (cursor == 0)
                    {
                        state = State.CERTIFICATE_LENGTH;
                        if (totalLength == 0)
                        {
                            onCredential();
                            return true;
                        }
                    }
                    break;
                }
                case CERTIFICATE_LENGTH:
                {
                    if (buffer.remaining() >= 4)
                    {
                        certificateLength = buffer.getInt() & 0x7F_FF_FF_FF;
                        state = State.CERTIFICATE;
                    }
                    else
                    {
                        state = State.CERTIFICATE_LENGTH_BYTES;
                        cursor = 4;
                    }
                    break;
                }
                case CERTIFICATE_LENGTH_BYTES:
                {
                    byte currByte = buffer.get();
                    --cursor;
                    certificateLength += (currByte & 0xFF) << 8 * cursor;
                    if (cursor == 0)
                    {
                        certificateLength &= 0x7F_FF_FF_FF;
                        state = State.CERTIFICATE;
                    }
                    break;
                }
                case CERTIFICATE:
                {
                    totalLength -= 4 + certificateLength;
                    certificate = new byte[certificateLength];
                    if (buffer.remaining() >= certificateLength)
                    {
                        buffer.get(certificate);
                        if (onCertificate())
                            return true;
                    }
                    else
                    {
                        state = State.CERTIFICATE_BYTES;
                        cursor = certificateLength;
                    }
                    break;
                }
                case CERTIFICATE_BYTES:
                {
                    certificate[certificateLength - cursor] = buffer.get();
                    --cursor;
                    if (cursor == 0)
                    {
                        if (onCertificate())
                            return true;
                    }
                    break;
                }
                default:
                {
                    throw new IllegalStateException();
                }
            }
        }
        return false;
    }

    private void checkSlotValid()
    {
        if (slot <= 0)
            throw new SessionException(SessionStatus.PROTOCOL_ERROR,
                    "Invalid slot " + slot + " for " + ControlFrameType.CREDENTIAL + " frame");
    }

    private boolean onCertificate()
    {
        certificates.add(deserializeCertificate(certificate));
        if (totalLength == 0)
        {
            onCredential();
            return true;
        }
        else
        {
            certificateLength = 0;
            state = State.CERTIFICATE_LENGTH;
        }
        return false;
    }

    private Certificate deserializeCertificate(byte[] bytes)
    {
        try
        {
            CertificateFactory certificateFactory = CertificateFactory.getInstance("X.509");
            return certificateFactory.generateCertificate(new ByteArrayInputStream(bytes));
        }
        catch (CertificateException x)
        {
            throw new SessionException(SessionStatus.PROTOCOL_ERROR, x);
        }
    }

    private void onCredential()
    {
        CredentialFrame frame = new CredentialFrame(controlFrameParser.getVersion(), slot,
                Arrays.copyOf(proof, proof.length), certificates.toArray(new Certificate[certificates.size()]));
        controlFrameParser.onControlFrame(frame);
        reset();
    }

    private void reset()
    {
        state = State.SLOT;
        totalLength = 0;
        cursor = 0;
        slot = 0;
        proofLength = 0;
        proof = null;
        certificateLength = 0;
        certificate = null;
        certificates.clear();
    }

    public enum State
    {
        SLOT, SLOT_BYTES, PROOF_LENGTH, PROOF_LENGTH_BYTES, PROOF, PROOF_BYTES,
        CERTIFICATE_LENGTH, CERTIFICATE_LENGTH_BYTES, CERTIFICATE, CERTIFICATE_BYTES
    }
}
