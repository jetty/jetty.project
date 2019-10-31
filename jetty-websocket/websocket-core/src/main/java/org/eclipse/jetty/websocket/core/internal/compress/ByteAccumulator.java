//
//  ========================================================================
//  Copyright (c) 1995-2019 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.websocket.core.internal.compress;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.websocket.core.MessageTooLargeException;

/**
 * Collect up 1 or more ByteBuffers for later transfer to a single {@link ByteBuffer}.
 * <p>
 * Used by decompression routines to fail if there is excessive inflation of the
 * decompressed data. (either maliciously or accidentally)
 * </p>
 */
public class ByteAccumulator
{
    private final long _maxFrameSize;
    private final List<ByteBuffer> _chunks = new ArrayList<>();
    private int _length = 0;

    public ByteAccumulator(long maxFrameSize)
    {
        _maxFrameSize = maxFrameSize;
    }

    public void addChunk(ByteBuffer buffer)
    {
        if (_maxFrameSize > 0 && _length + buffer.remaining() > _maxFrameSize)
            throw new MessageTooLargeException(String.format("Decompressed Message [%,d b] is too large [max %,d b]", this._length + _length, _maxFrameSize));

        _chunks.add(buffer);
        _length += buffer.remaining();
    }

    public int size()
    {
        return _length;
    }

    public ByteBuffer getBytes(ByteBufferPool bufferPool)
    {
        if (_length == 0)
            return BufferUtil.EMPTY_BUFFER;

        ByteBuffer buffer = bufferPool.acquire(_length, false);
        BufferUtil.clearToFill(buffer);
        transferTo(buffer);
        BufferUtil.flipToFlush(buffer, 0);
        return buffer;
    }

    public ByteBuffer getBytes()
    {
        if (_length == 0)
            return BufferUtil.EMPTY_BUFFER;

        ByteBuffer buffer = ByteBuffer.allocate(_length);
        BufferUtil.clearToFill(buffer);
        transferTo(buffer);
        BufferUtil.flipToFlush(buffer, 0);
        return buffer;
    }

    public void transferTo(ByteBuffer bufferInFillMode)
    {
        if (bufferInFillMode.remaining() < _length)
        {
            throw new IllegalArgumentException(String.format("Not enough space in ByteBuffer remaining [%d] for accumulated buffers length [%d]",
                bufferInFillMode.remaining(), _length));
        }

        for (ByteBuffer chunk : _chunks)
        {
            bufferInFillMode.put(chunk);
        }
    }
}
