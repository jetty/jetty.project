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

import org.eclipse.jetty.tls.ext.EarlyDataExtension;
import org.eclipse.jetty.util.buffer.RetainableByteBuffer;

public class EarlyDataExtensionParser implements ExtensionParser
{
    private final Listener listener;
    private State state = State.TOTAL_LENGTH;
    private int totalLength;
    private long maxData;
    private int cursor;

    public EarlyDataExtensionParser(Listener listener)
    {
        this.listener = listener;
    }

    @Override
    public int type()
    {
        return EarlyDataExtension.CODE;
    }

    @Override
    public int parse(RetainableByteBuffer buffer)
    {
        while (true)
        {
            int remaining = Math.toIntExact(buffer.remaining());
            if (remaining == 0)
                return -1;
            switch (state)
            {
                case TOTAL_LENGTH ->
                {
                    if (remaining > 1)
                    {
                        totalLength = buffer.getShortAsInt();
                        if (totalLength != 0 && totalLength != 4)
                            throw new IllegalStateException("invalid early data extension length " + totalLength);
                        if (totalLength == 0)
                            return maxDataComplete();
                        state = State.MAX_DATA;
                    }
                    else
                    {
                        cursor = 2;
                        state = State.TOTAL_LENGTH_BYTES;
                    }
                }
                case TOTAL_LENGTH_BYTES ->
                {
                    int b = buffer.getByteAsInt();
                    --cursor;
                    totalLength += b << (8 * cursor);
                    if (cursor == 0)
                    {
                        if (totalLength != 0 && totalLength != 4)
                            throw new IllegalStateException("invalid supported versions extension length " + totalLength);
                        if (totalLength == 0)
                            return maxDataComplete();
                        state = State.MAX_DATA;
                    }
                }
                case MAX_DATA ->
                {
                    if (remaining >= 4)
                    {
                        maxData = Integer.toUnsignedLong(buffer.getInt());
                        return maxDataComplete();
                    }
                    else
                    {
                        cursor = 4;
                        state = State.MAX_DATA_BYTES;
                    }
                }
                case MAX_DATA_BYTES ->
                {
                    long b = buffer.getByteAsInt();
                    --cursor;
                    maxData += b << (8 * cursor);
                    if (cursor == 0)
                        return maxDataComplete();
                }
            }
        }
    }

    private int maxDataComplete()
    {
        int result = 2 + totalLength;
        long max = maxData;
        state = State.TOTAL_LENGTH;
        totalLength = 0;
        maxData = 0;
        listener.onExtension(new EarlyDataExtension(max));
        return result;
    }

    private enum State
    {
        TOTAL_LENGTH,
        TOTAL_LENGTH_BYTES,
        MAX_DATA,
        MAX_DATA_BYTES
    }
}
