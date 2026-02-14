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
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.tls.KeyShare;
import org.eclipse.jetty.tls.NamedGroup;
import org.eclipse.jetty.tls.ext.KeyShareExtension;
import org.eclipse.jetty.util.BufferUtil;

public class KeyShareExtensionParser implements ExtensionParser
{
    private final List<KeyShare> shares = new ArrayList<>();
    private final ExtensionParser.Listener listener;
    private final boolean client;
    private State state = State.TOTAL_LENGTH;
    private int totalLength;
    private int listLength;
    private int group;
    private int keyExchangeLength;
    private byte[] keyExchange;
    private int cursor;

    public KeyShareExtensionParser(Listener listener, boolean client)
    {
        this.listener = listener;
        this.client = client;
    }

    @Override
    public int type()
    {
        return KeyShareExtension.CODE;
    }

    @Override
    public int parse(RetainableByteBuffer buffer)
    {
        while (true)
        {
            ByteBuffer byteBuffer = buffer.getByteBuffer();
            int remaining = byteBuffer.remaining();
            if (remaining == 0 && state != State.KEY_EXCHANGE)
                return -1;
            switch (state)
            {
                case TOTAL_LENGTH ->
                {
                    if (remaining > 1)
                    {
                        totalLength = byteBuffer.getShort() & 0xFFFF;
                        // The format of this extension is different depending on
                        // whether is in a ClientHello, ServerHello or RetryHelloRequest.
                        // RFC-8446[4.2.8].
                        boolean isRetryFormat = client && totalLength == 2;
                        if (!isRetryFormat && totalLength < 4)
                            throw new IllegalStateException("invalid key share extension length " + totalLength);
                        state = client ? State.GROUP : State.LIST_LENGTH;
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
                        boolean isRetryFormat = client && totalLength == 2;
                        if (!isRetryFormat && totalLength < 4)
                            throw new IllegalStateException("invalid key share extension length " + totalLength);
                        state = client ? State.GROUP : State.LIST_LENGTH;
                    }
                }
                case LIST_LENGTH ->
                {
                    if (remaining > 1)
                    {
                        listLength = byteBuffer.getShort() & 0xFFFF;
                        state = State.GROUP;
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
                        state = State.GROUP;
                }
                case GROUP ->
                {
                    if (remaining > 1)
                    {
                        group = byteBuffer.getShort() & 0xFFFF;
                        if (!client)
                            listLength -= 2;
                        state = totalLength == 2 ? State.KEY_EXCHANGE : State.KEY_EXCHANGE_LENGTH;
                    }
                    else
                    {
                        cursor = 2;
                        state = State.GROUP_BYTES;
                    }
                }
                case GROUP_BYTES ->
                {
                    int b = byteBuffer.get() & 0xFF;
                    --cursor;
                    group += b << (8 * cursor);
                    if (cursor == 0)
                    {
                        if (!client)
                            listLength -= 2;
                        state = totalLength == 2 ? State.KEY_EXCHANGE : State.KEY_EXCHANGE_LENGTH;
                    }
                }
                case KEY_EXCHANGE_LENGTH ->
                {
                    if (remaining > 1)
                    {
                        keyExchangeLength = byteBuffer.getShort() & 0xFFFF;
                        if (!client)
                            listLength -= 2;
                        keyExchange = new byte[keyExchangeLength];
                        state = State.KEY_EXCHANGE_BYTES;
                    }
                    else
                    {
                        cursor = 2;
                        state = State.KEY_EXCHANGE_LENGTH_BYTES;
                    }
                }
                case KEY_EXCHANGE_LENGTH_BYTES ->
                {
                    int b = byteBuffer.get() & 0xFF;
                    --cursor;
                    keyExchangeLength += b << (8 * cursor);
                    if (cursor == 0)
                    {
                        if (!client)
                            listLength -= 2;
                        keyExchange = new byte[keyExchangeLength];
                        state = State.KEY_EXCHANGE_BYTES;
                    }
                }
                case KEY_EXCHANGE_BYTES ->
                {
                    int offset = keyExchange.length - keyExchangeLength;
                    int length = Math.min(keyExchangeLength, remaining);
                    byteBuffer.get(keyExchange, offset, length);
                    if (!client)
                        listLength -= length;
                    keyExchangeLength -= length;
                    if (keyExchangeLength == 0)
                        state = State.KEY_EXCHANGE;
                }
                case KEY_EXCHANGE ->
                {
                    int result = keyExchangeComplete();
                    if (result > 0)
                        return result;
                }
            }
        }
    }

    private int keyExchangeComplete()
    {
        NamedGroup namedGroup = NamedGroup.from(group);
        if (namedGroup == null)
            throw new IllegalArgumentException("unknown named group " + Integer.toHexString(group));
        shares.add(new KeyShare(namedGroup, keyExchange != null ? keyExchange : BufferUtil.EMPTY_BYTES));
        group = 0;
        keyExchange = null;
        if (listLength == 0)
        {
            int result = 2 + totalLength;
            totalLength = 0;
            List<KeyShare> keyShares = List.copyOf(shares);
            shares.clear();
            state = State.TOTAL_LENGTH;
            listener.onExtension(new KeyShareExtension(keyShares));
            return result;
        }
        else
        {
            state = State.GROUP;
            return -1;
        }
    }

    private enum State
    {
        TOTAL_LENGTH,
        TOTAL_LENGTH_BYTES,
        LIST_LENGTH,
        LIST_LENGTH_BYTES,
        GROUP,
        GROUP_BYTES,
        KEY_EXCHANGE_LENGTH,
        KEY_EXCHANGE_LENGTH_BYTES,
        KEY_EXCHANGE_BYTES,
        KEY_EXCHANGE
    }
}
