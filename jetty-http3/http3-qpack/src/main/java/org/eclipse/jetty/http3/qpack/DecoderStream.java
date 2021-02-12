//
// ========================================================================
// Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.http3.qpack;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.NullByteBufferPool;
import org.eclipse.jetty.util.BufferUtil;

public class DecoderStream
{
    private final OutputStream _outputStream;
    private final ByteBufferPool _bufferPool;

    public DecoderStream(OutputStream outputStream)
    {
        this (outputStream, new NullByteBufferPool());
    }

    public DecoderStream(OutputStream outputStream, ByteBufferPool bufferPool)
    {
        _outputStream = outputStream;
        _bufferPool = bufferPool;
    }

    void sendSectionAcknowledgment(int streamId) throws IOException
    {
        int size = NBitInteger.octectsNeeded(7, streamId) + 1;
        ByteBuffer buffer = _bufferPool.acquire(size, false);
        BufferUtil.clearToFill(buffer);
        buffer.put((byte)0x80);
        NBitInteger.encode(buffer, 7, streamId);
        BufferUtil.flipToFlush(buffer, 0);
        BufferUtil.writeTo(buffer, _outputStream);
    }

    void sendStreamCancellation(int streamId) throws IOException
    {
        int size = NBitInteger.octectsNeeded(6, streamId) + 1;
        ByteBuffer buffer = _bufferPool.acquire(size, false);
        BufferUtil.clearToFill(buffer);
        buffer.put((byte)0x40);
        NBitInteger.encode(buffer, 6, streamId);
        BufferUtil.flipToFlush(buffer, 0);
        BufferUtil.writeTo(buffer, _outputStream);
    }

    void sendInsertCountIncrement(int increment) throws IOException
    {
        int size = NBitInteger.octectsNeeded(6, increment) + 1;
        ByteBuffer buffer = _bufferPool.acquire(size, false);
        BufferUtil.clearToFill(buffer);
        buffer.put((byte)0x00);
        NBitInteger.encode(buffer, 6, increment);
        BufferUtil.flipToFlush(buffer, 0);
        BufferUtil.writeTo(buffer, _outputStream);
    }
}
