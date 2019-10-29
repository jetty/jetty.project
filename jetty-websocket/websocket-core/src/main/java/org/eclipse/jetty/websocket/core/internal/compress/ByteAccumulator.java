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

    public ByteBuffer getBytes()
    {
        ByteBuffer buffer = ByteBuffer.allocate(_length);
        BufferUtil.clearToFill(buffer);

        for (ByteBuffer chunk : _chunks)
        {
            BufferUtil.put(chunk, buffer);
        }

        _chunks.clear();
        _length = 0;

        BufferUtil.flipToFlush(buffer, 0);
        return buffer;
    }
}
