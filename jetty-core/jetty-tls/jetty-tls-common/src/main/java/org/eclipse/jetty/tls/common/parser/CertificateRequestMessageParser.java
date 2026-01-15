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
import java.util.List;

import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.tls.CertificateRequestMessage;
import org.eclipse.jetty.tls.Message;
import org.eclipse.jetty.tls.ext.Extension;

public class CertificateRequestMessageParser implements MessageParser
{
    private final ExtensionsParser extensionsParser;
    private State state = State.CONTEXT_LENGTH;
    private int cursor;
    private byte[] context;

    public CertificateRequestMessageParser(ExtensionsParser extensionsParser)
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
                        state = State.EXTENSIONS;
                    }
                }
                case EXTENSIONS ->
                {
                    List<Extension> extensions = extensionsParser.parse(buffer);
                    if (extensions == null)
                        return null;
                    CertificateRequestMessage message = new CertificateRequestMessage(context, extensions);
                    context = null;
                    state = State.CONTEXT_LENGTH;
                    return message;
                }
            }
        }
    }

    private enum State
    {
        CONTEXT_LENGTH,
        CONTEXT,
        EXTENSIONS
    }
}
