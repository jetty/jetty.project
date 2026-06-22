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
import org.eclipse.jetty.tls.ext.UnknownExtension;

public class UnknownExtensionParser implements ExtensionParser
{
    private final int code;
    private final Listener listener;
    private State state = State.TOTAL_LENGTH;
    private int totalLength;
    private int cursor;
    private byte[] bytes;

    public UnknownExtensionParser(int code, Listener listener)
    {
        this.code = code;
        this.listener = listener;
    }

    @Override
    public int type()
    {
        return code;
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
                        totalLength = byteBuffer.getShort() & 0xFFFF;
                        bytes = new byte[totalLength];
                        state = State.DATA;
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
                        bytes = new byte[totalLength];
                        state = State.DATA;
                    }
                }
                case DATA ->
                {
                    int length = Math.min(totalLength, byteBuffer.remaining());
                    byteBuffer.get(bytes, bytes.length - totalLength, length);
                    totalLength -= length;
                    if (totalLength == 0)
                    {
                        int result = 2 + bytes.length;
                        state = State.TOTAL_LENGTH;
                        listener.onExtension(new UnknownExtension(type(), bytes));
                        return result;
                    }
                }
            }
        }
    }

    private enum State
    {
        TOTAL_LENGTH,
        TOTAL_LENGTH_BYTES,
        DATA
    }
}
