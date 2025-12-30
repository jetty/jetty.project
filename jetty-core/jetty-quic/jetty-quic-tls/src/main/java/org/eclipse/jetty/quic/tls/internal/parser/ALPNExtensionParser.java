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

package org.eclipse.jetty.quic.tls.internal.parser;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.quic.tls.message.ALPNExtension;
import org.eclipse.jetty.quic.tls.message.Extension;

public class ALPNExtensionParser implements ExtensionParser
{
    private final List<String> protocols = new ArrayList<>();
    private final Listener listener;
    private State state = State.TOTAL_LENGTH;
    private int totalLength;
    private int listLength;
    private int length;
    private byte[] protocol;
    private int cursor;

    public ALPNExtensionParser(Listener listener)
    {
        this.listener = listener;
    }

    @Override
    public Extension.Type type()
    {
        return Extension.Type.ALPN;
    }

    @Override
    public int parse(RetainableByteBuffer buffer)
    {
        while (true)
        {
            ByteBuffer byteBuffer = buffer.getByteBuffer();
            int remaining = byteBuffer.remaining();
            if (remaining == 0)
                return -1;
            switch (state)
            {
                case TOTAL_LENGTH ->
                {
                    if (remaining > 1)
                    {
                        totalLength = (byteBuffer.getShort() & 0xFFFF);
                        if (totalLength < 4)
                            throw new IllegalStateException("invalid alpn extension length " + totalLength);
                        state = State.LIST_LENGTH;
                    }
                    else
                    {
                        cursor = 2;
                        state = State.TOTAL_LENGTH_BYTES;
                    }
                }
                case TOTAL_LENGTH_BYTES ->
                {
                    int b = byteBuffer.get() & 0xFF;
                    --cursor;
                    totalLength += b << (8 * cursor);
                    if (cursor == 0)
                    {
                        if (totalLength < 4)
                            throw new IllegalStateException("invalid alpn extension length " + totalLength);
                        state = State.LIST_LENGTH;
                    }
                }
                case LIST_LENGTH ->
                {
                    if (remaining > 1)
                    {
                        listLength = (byteBuffer.getShort() & 0xFFFF);
                        if (listLength < 2)
                            throw new IllegalStateException("invalid alpn list length " + listLength);
                        state = State.LENGTH;
                    }
                    else
                    {
                        cursor = 2;
                        state = State.LIST_LENGTH_BYTES;
                    }
                }
                case LIST_LENGTH_BYTES ->
                {
                    int b = byteBuffer.get() & 0xFF;
                    --cursor;
                    listLength += b << (8 * cursor);
                    if (cursor == 0)
                    {
                        if (listLength < 2)
                            throw new IllegalStateException("invalid alpn list length " + listLength);
                        state = State.LENGTH;
                    }
                }
                case LENGTH ->
                {
                    length = byteBuffer.get() & 0xFF;
                    listLength -= 1;
                    protocol = new byte[length];
                    state = State.PROTOCOL;
                }
                case PROTOCOL ->
                {
                    if (remaining >= length)
                    {
                        byteBuffer.get(protocol);
                        listLength -= length;
                        int result = protocolComplete();
                        if (result > 0)
                            return result;
                    }
                    else
                    {
                        cursor = length;
                        state = State.PROTOCOL_BYTES;
                    }
                }
                case PROTOCOL_BYTES ->
                {
                    protocol[length - cursor] = byteBuffer.get();
                    --cursor;
                    if (cursor == 0)
                    {
                        listLength -= length;
                        int result = protocolComplete();
                        if (result > 0)
                            return result;
                    }
                }
            }
        }
    }

    private int protocolComplete()
    {
        protocols.add(new String(protocol, StandardCharsets.UTF_8));
        length = 0;
        protocol = null;
        if (listLength == 0)
        {
            int result = totalLength;
            totalLength = 0;
            List<String> alpnProtocols = List.copyOf(protocols);
            protocols.clear();
            state = State.TOTAL_LENGTH;
            listener.onExtension(new ALPNExtension(alpnProtocols));
            return result;
        }
        else
        {
            state = State.LENGTH;
            return -1;
        }
    }

    private enum State
    {
        TOTAL_LENGTH,
        TOTAL_LENGTH_BYTES,
        LIST_LENGTH,
        LIST_LENGTH_BYTES,
        LENGTH,
        PROTOCOL,
        PROTOCOL_BYTES
    }
}
