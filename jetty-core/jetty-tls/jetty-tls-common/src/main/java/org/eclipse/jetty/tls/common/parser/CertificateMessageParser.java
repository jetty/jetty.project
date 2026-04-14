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

import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.tls.CertificateMessage;
import org.eclipse.jetty.tls.Message;
import org.eclipse.jetty.tls.TLSException;
import org.eclipse.jetty.tls.ext.Extension;

public class CertificateMessageParser implements MessageParser
{
    private final List<CertificateMessage.Entry> entries = new ArrayList<>();
    private final ExtensionsParser extensionsParser;
    private State state = State.CONTEXT_LENGTH;
    private int cursor;
    private byte[] context;
    private int entriesLength;
    private int certificateLength;
    private byte[] certificate;

    public CertificateMessageParser(ExtensionsParser extensionsParser)
    {
        this.extensionsParser = extensionsParser;
    }

    @Override
    public Message parse(int messageLength, RetainableByteBuffer buffer)
    {
        while (true)
        {
            ByteBuffer byteBuffer = buffer.getByteBuffer();
            int remaining = byteBuffer.remaining();
            if (remaining == 0)
                return null;
            switch (state)
            {
                case CONTEXT_LENGTH ->
                {
                    int length = byteBuffer.get() & 0xFF;
                    context = new byte[length];
                    state = State.CONTEXT;
                }
                case CONTEXT ->
                {
                    int offset = cursor;
                    int length = Math.min(context.length - cursor, remaining);
                    byteBuffer.get(context, offset, length);
                    cursor += length;
                    if (cursor == context.length)
                    {
                        cursor = 0;
                        state = State.ENTRIES_LENGTH;
                    }
                }
                case ENTRIES_LENGTH ->
                {
                    if (remaining > 2)
                    {
                        entriesLength = ((byteBuffer.getShort() & 0xFFFF) << 8) | (byteBuffer.get() & 0xFF);
                        state = State.CERTIFICATE_LENGTH;
                    }
                    else
                    {
                        cursor = 3;
                        state = State.ENTRIES_LENGTH_BYTES;
                    }
                }
                case ENTRIES_LENGTH_BYTES ->
                {
                    int b = byteBuffer.get() & 0xFF;
                    --cursor;
                    entriesLength += b << (8 * cursor);
                    if (cursor == 0)
                        state = State.CERTIFICATE_LENGTH;
                }
                case CERTIFICATE_LENGTH ->
                {
                    if (remaining > 2)
                    {
                        certificateLength = ((byteBuffer.getShort() & 0xFFFF) << 8) | (byteBuffer.get() & 0xFF);
                        certificate = new byte[certificateLength];
                        entriesLength -= 3;
                        state = State.CERTIFICATE;
                    }
                    else
                    {
                        cursor = 3;
                        state = State.CERTIFICATE_LENGTH_BYTES;
                    }
                }
                case CERTIFICATE_LENGTH_BYTES ->
                {
                    int b = byteBuffer.get() & 0xFF;
                    --cursor;
                    certificateLength += b << (8 * cursor);
                    if (cursor == 0)
                    {
                        certificate = new byte[certificateLength];
                        entriesLength -= 3;
                        state = State.CERTIFICATE;
                    }
                }
                case CERTIFICATE ->
                {
                    int offset = cursor;
                    int length = Math.min(certificateLength - cursor, remaining);
                    byteBuffer.get(certificate, offset, length);
                    cursor += length;
                    if (cursor == certificateLength)
                    {
                        cursor = 0;
                        entriesLength -= certificateLength;
                        state = State.EXTENSIONS;
                    }
                }
                case EXTENSIONS ->
                {
                    try
                    {
                        List<Extension> extensions = extensionsParser.parse(buffer, extensionsLength -> entriesLength -= extensionsLength);
                        if (extensions == null)
                            return null;
                        CertificateFactory certificateFactory = CertificateFactory.getInstance("X509");
                        X509Certificate x509 = (X509Certificate)certificateFactory.generateCertificate(new ByteArrayInputStream(certificate));
                        CertificateMessage.Entry entry = new CertificateMessage.Entry(x509, extensions);
                        entries.add(entry);
                        certificateLength = 0;
                        certificate = null;
                        if (entriesLength == 0)
                        {
                            CertificateMessage message = new CertificateMessage(context, List.copyOf(entries));
                            context = null;
                            entries.clear();
                            state = State.CONTEXT_LENGTH;
                            return message;
                        }
                        else
                        {
                            state = State.CERTIFICATE_LENGTH;
                        }
                    }
                    catch (CertificateException x)
                    {
                        throw new TLSException(TLSException.Alert.BAD_CERTIFICATE, x);
                    }
                }
            }
        }
    }

    private enum State
    {
        CONTEXT_LENGTH,
        CONTEXT,
        ENTRIES_LENGTH,
        ENTRIES_LENGTH_BYTES,
        CERTIFICATE_LENGTH,
        CERTIFICATE_LENGTH_BYTES,
        CERTIFICATE,
        EXTENSIONS
    }
}
