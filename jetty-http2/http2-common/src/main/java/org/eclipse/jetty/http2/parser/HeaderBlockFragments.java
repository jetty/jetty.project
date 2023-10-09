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

package org.eclipse.jetty.http2.parser;

import java.nio.ByteBuffer;

import org.eclipse.jetty.http2.frames.PriorityFrame;
import org.eclipse.jetty.io.ByteBufferPool;

public class HeaderBlockFragments
{
    private final ByteBufferPool byteBufferPool;
    private final int maxCapacity;
    private PriorityFrame priorityFrame;
    private int streamId;
    private boolean endStream;
    private ByteBuffer storage;

    @Deprecated
    public HeaderBlockFragments(ByteBufferPool byteBufferPool)
    {
        this(byteBufferPool, 8192);
    }

    public HeaderBlockFragments(ByteBufferPool byteBufferPool, int maxCapacity)
    {
        this.byteBufferPool = byteBufferPool;
        this.maxCapacity = maxCapacity;
    }

    public ByteBufferPool getByteBufferPool()
    {
        return byteBufferPool;
    }

    void reset()
    {
        priorityFrame = null;
        streamId = 0;
        endStream = false;
        storage = null;
    }

    public boolean storeFragment(ByteBuffer fragment, int length, boolean last)
    {
        if (storage == null)
        {
            if (length > maxCapacity)
                return false;
            int capacity = last ? length : length * 2;
            storage = byteBufferPool.acquire(capacity, fragment.isDirect());
            storage.clear();
        }

        // Grow the storage if necessary.
        if (storage.remaining() < length)
        {
            if (storage.position() + length > maxCapacity)
                return false;
            int space = last ? length : length * 2;
            int capacity = storage.position() + space;
            ByteBuffer newStorage = byteBufferPool.acquire(capacity, storage.isDirect());
            newStorage.clear();
            storage.flip();
            newStorage.put(storage);
            byteBufferPool.release(storage);
            storage = newStorage;
        }

        // Copy the fragment into the storage.
        int limit = fragment.limit();
        fragment.limit(fragment.position() + length);
        storage.put(fragment);
        fragment.limit(limit);
        return true;
    }

    public PriorityFrame getPriorityFrame()
    {
        return priorityFrame;
    }

    public void setPriorityFrame(PriorityFrame priorityFrame)
    {
        this.priorityFrame = priorityFrame;
    }

    public boolean isEndStream()
    {
        return endStream;
    }

    public void setEndStream(boolean endStream)
    {
        this.endStream = endStream;
    }

    public ByteBuffer complete()
    {
        storage.flip();
        return storage;
    }

    public int getStreamId()
    {
        return streamId;
    }

    public void setStreamId(int streamId)
    {
        this.streamId = streamId;
    }
}
