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

import org.eclipse.jetty.http2.frames.PriorityFrame;
import org.eclipse.jetty.io.WritableBufferPool;
import org.eclipse.jetty.util.buffer.RetainableByteBuffer;

public class HeaderBlockFragments
{
    private final WritableBufferPool bufferPool;
    private final boolean directness;
    private final int maxCapacity;
    private PriorityFrame priorityFrame;
    private int streamId;
    private boolean endStream;
    private RetainableByteBuffer.Mutable storage;

    public HeaderBlockFragments(WritableBufferPool bufferPool, boolean directness, int maxCapacity)
    {
        this.bufferPool = bufferPool;
        this.directness = directness;
        this.maxCapacity = maxCapacity;
    }

    void reset()
    {
        priorityFrame = null;
        streamId = 0;
        endStream = false;
        storage = null;
    }

    public boolean storeFragment(RetainableByteBuffer fragment, int length, boolean last)
    {
        if (storage == null)
        {
            if (maxCapacity > 0 && length > maxCapacity)
                return false;
            int capacity = last ? length : length * 2;
            storage = bufferPool.acquire(capacity, directness);
        }

        // Grow the storage if necessary.
        if (storage.space() < length)
        {
            if (maxCapacity > 0 && (storage.remaining() + length) > maxCapacity)
                return false;
            int space = last ? length : length * 2;
            // TODO overflow?
            int capacity = Math.toIntExact(storage.remaining() + space);
            RetainableByteBuffer.Mutable largerStorage = bufferPool.acquire(capacity, directness);
            largerStorage.put(storage);
            storage.release();
            storage = largerStorage;
        }

        long l = fragment.remaining() > length ? length : fragment.remaining();
        RetainableByteBuffer slice = fragment.sliceAndConsume(l);

        // Copy the fragment into the storage.
        // TODO find a way to limit the size of the copy without slicing?
        storage.put(slice);
        slice.release();

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

    public RetainableByteBuffer complete()
    {
        RetainableByteBuffer rb = storage;
        storage = null;
        return rb;
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
