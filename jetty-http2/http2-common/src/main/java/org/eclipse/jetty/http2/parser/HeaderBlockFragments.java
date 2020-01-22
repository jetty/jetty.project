//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.http2.parser;

import java.nio.ByteBuffer;

import org.eclipse.jetty.http2.frames.PriorityFrame;

public class HeaderBlockFragments
{
    private PriorityFrame priorityFrame;
    private boolean endStream;
    private int streamId;
    private ByteBuffer storage;

    public void storeFragment(ByteBuffer fragment, int length, boolean last)
    {
        if (storage == null)
        {
            int space = last ? length : length * 2;
            storage = ByteBuffer.allocate(space);
        }

        // Grow the storage if necessary.
        if (storage.remaining() < length)
        {
            int space = last ? length : length * 2;
            int capacity = storage.position() + space;
            ByteBuffer newStorage = ByteBuffer.allocate(capacity);
            storage.flip();
            newStorage.put(storage);
            storage = newStorage;
        }

        // Copy the fragment into the storage.
        int limit = fragment.limit();
        fragment.limit(fragment.position() + length);
        storage.put(fragment);
        fragment.limit(limit);
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
        ByteBuffer result = storage;
        storage = null;
        result.flip();
        return result;
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
