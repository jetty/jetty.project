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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.tls.ext.ServerNameExtension;

/// Extension format:
/// ```
/// extension
///   type (2): 0x0000        00 00
///   length (2)              00 10
///   server_name
///     list_length (2)       00 0E
///     type (1): 0x00        00
///     length (2)            00 0B
///     name (N)              webtide.com
/// ```
public class ServerNameExtensionParser implements ExtensionParser
{
    private final List<String> names = new ArrayList<>();
    private final ExtensionParser.Listener listener;
    private State state = State.TOTAL_LENGTH;
    private int totalLength;
    private int listLength;
    private int length;
    private byte[] name;
    private int cursor;

    public ServerNameExtensionParser(Listener listener)
    {
        this.listener = listener;
    }

    @Override
    public int type()
    {
        return ServerNameExtension.CODE;
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
                            throw new IllegalStateException("invalid server name extension length " + totalLength);
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
                            throw new IllegalStateException("invalid server name extension length " + totalLength);
                        state = State.LIST_LENGTH;
                    }
                }
                case LIST_LENGTH ->
                {
                    if (remaining > 1)
                    {
                        listLength = (byteBuffer.getShort() & 0xFFFF);
                        if (listLength < 2)
                            throw new IllegalStateException("invalid server name list length " + listLength);
                        state = State.TYPE;
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
                            throw new IllegalStateException("invalid server name list length " + listLength);
                        state = State.TYPE;
                    }
                }
                case TYPE ->
                {
                    byte type = byteBuffer.get();
                    if (type != 0x00)
                        throw new IllegalStateException("invalid server name type " + type);
                    listLength -= 1;
                    state = State.LENGTH;
                }
                case LENGTH ->
                {
                    if (remaining > 1)
                    {
                        length = (byteBuffer.getShort() & 0xFFFF);
                        listLength -= 2;
                        name = new byte[length];
                        state = State.NAME;
                    }
                    else
                    {
                        cursor = 2;
                        state = State.LENGTH_BYTES;
                    }
                }
                case LENGTH_BYTES ->
                {
                    int b = byteBuffer.get() & 0xFF;
                    --cursor;
                    length += b << (8 * cursor);
                    if (cursor == 0)
                    {
                        listLength -= 2;
                        name = new byte[length];
                        state = State.NAME;
                    }
                }
                case NAME ->
                {
                    if (remaining >= length)
                    {
                        byteBuffer.get(name);
                        listLength -= length;
                        int result = nameComplete();
                        if (result > 0)
                            return result;
                    }
                    else
                    {
                        cursor = length;
                        state = State.NAME_BYTES;
                    }
                }
                case NAME_BYTES ->
                {
                    name[length - cursor] = byteBuffer.get();
                    --cursor;
                    if (cursor == 0)
                    {
                        listLength -= length;
                        int result = nameComplete();
                        if (result > 0)
                            return result;
                    }
                }
            }
        }
    }

    private int nameComplete()
    {
        names.add(new String(name, StandardCharsets.US_ASCII));
        length = 0;
        name = null;
        if (listLength == 0)
        {
            if (names.size() > 1)
                throw new IllegalStateException("invalid server name list " + names);
            String serverName = names.getFirst();
            int result = 2 + totalLength;
            totalLength = 0;
            names.clear();
            state = State.TOTAL_LENGTH;
            listener.onExtension(new ServerNameExtension(serverName));
            return result;
        }
        else
        {
            state = State.TYPE;
            return -1;
        }
    }

    private enum State
    {
        TOTAL_LENGTH,
        TOTAL_LENGTH_BYTES,
        LIST_LENGTH,
        LIST_LENGTH_BYTES,
        TYPE,
        LENGTH,
        LENGTH_BYTES,
        NAME,
        NAME_BYTES
    }
}
