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
import org.eclipse.jetty.tls.ext.ServerPreSharedKeyExtension;

public class ServerPreSharedKeyExtensionParser implements ExtensionParser
{
    private final Listener listener;
    private State state = State.TOTAL_LENGTH;
    private int totalLength;
    private int identity;
    private int cursor;

    public ServerPreSharedKeyExtensionParser(Listener listener)
    {
        this.listener = listener;
    }

    @Override
    public int type()
    {
        return ServerPreSharedKeyExtension.CODE;
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
                        if (totalLength != 2)
                            throw new IllegalStateException("invalid pre-shared key extension length " + totalLength);
                        state = State.IDENTITY;
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
                        if (totalLength != 2)
                            throw new IllegalStateException("invalid pre-shared key extension length " + totalLength);
                        state = State.IDENTITY;
                    }
                }
                case IDENTITY ->
                {
                    if (remaining >= 2)
                    {
                        identity = byteBuffer.getShort() & 0xFFFF;
                        return identityComplete();
                    }
                    else
                    {
                        cursor = 2;
                        state = State.IDENTITY_BYTES;
                    }
                }
                case IDENTITY_BYTES ->
                {
                    int b = byteBuffer.get() & 0xFF;
                    --cursor;
                    identity += b << (8 * cursor);
                    if (cursor == 0)
                        return identityComplete();
                }
            }
        }
    }

    private int identityComplete()
    {
        int result = 2 + totalLength;
        int selected = identity;
        state = State.TOTAL_LENGTH;
        totalLength = 0;
        identity = 0;
        listener.onExtension(new ServerPreSharedKeyExtension(selected));
        return result;
    }

    private enum State
    {
        TOTAL_LENGTH,
        TOTAL_LENGTH_BYTES,
        IDENTITY,
        IDENTITY_BYTES
    }
}
