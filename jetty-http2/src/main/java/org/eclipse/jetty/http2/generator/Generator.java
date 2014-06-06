//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.http2.generator;

import java.nio.ByteBuffer;
import java.util.Map;

import org.eclipse.jetty.hpack.HpackEncoder;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http2.frames.Flag;
import org.eclipse.jetty.http2.frames.Frame;
import org.eclipse.jetty.http2.frames.FrameType;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.util.BufferUtil;

public class Generator
{
    private final ByteBufferPool byteBufferPool;
    private final HpackEncoder encoder;

    public Generator(ByteBufferPool byteBufferPool)
    {
        this.byteBufferPool = byteBufferPool;
        this.encoder = new HpackEncoder(byteBufferPool);
    }

    public ByteBufferPool.Lease generateData(int streamId, ByteBuffer data, boolean last, boolean compress, byte[] paddingBytes)
    {
        if (streamId < 0)
            throw new IllegalArgumentException("Invalid stream id: " + streamId);
        int paddingLength = paddingBytes == null ? 0 : paddingBytes.length;
        // Leave space for at least one byte of content.
        if (paddingLength > Frame.MAX_LENGTH - 3)
            throw new IllegalArgumentException("Invalid padding length: " + paddingLength);

        int extraPaddingBytes = paddingLength > 0xFF ? 2 : paddingLength > 0 ? 1 : 0;

        // TODO: here we should compress the data, and then reason on the data length !

        int dataLength = data.remaining();

        ByteBufferPool.Lease lease = new ByteBufferPool.Lease(byteBufferPool);

        // Can we fit just one frame ?
        if (dataLength + extraPaddingBytes + paddingLength <= Frame.MAX_LENGTH)
        {
            generateData(lease, streamId, data, last, compress, extraPaddingBytes, paddingBytes);
        }
        else
        {
            int dataBytesPerFrame = Frame.MAX_LENGTH - extraPaddingBytes - paddingLength;
            int frames = dataLength / dataBytesPerFrame;
            if (frames * dataBytesPerFrame != dataLength)
            {
                ++frames;
            }
            int limit = data.limit();
            for (int i = 1; i <= frames; ++i)
            {
                data.limit(Math.min(dataBytesPerFrame * i, limit));
                ByteBuffer slice = data.slice();
                data.position(data.limit());
                generateData(lease, streamId, slice, i == frames && last, compress, extraPaddingBytes, paddingBytes);
            }
        }
        return lease;
    }

    public ByteBufferPool.Lease generateHeaders(int streamId, HttpFields headers, boolean contentFollows, byte[] paddingBytes)
    {
        if (streamId < 0)
            throw new IllegalArgumentException("Invalid stream id: " + streamId);
        int paddingLength = paddingBytes == null ? 0 : paddingBytes.length;
        // Leave space for at least one byte of content.
        if (paddingLength > Frame.MAX_LENGTH - 3)
            throw new IllegalArgumentException("Invalid padding length: " + paddingLength);

        int extraPaddingBytes = paddingLength > 0xFF ? 2 : paddingLength > 0 ? 1 : 0;

        ByteBufferPool.Lease hpackBuffers = encoder.encode(headers);

        long hpackLength = hpackBuffers.getTotalLength();

        long length = extraPaddingBytes + hpackLength + paddingLength;
        if (length > Frame.MAX_LENGTH)
            throw new IllegalArgumentException("Invalid headers, too big");

        int flags = Flag.END_HEADERS;
        if (!contentFollows)
            flags |= Flag.END_STREAM;
        if (extraPaddingBytes > 0)
            flags |= Flag.PADDING_LOW;
        if (extraPaddingBytes > 1)
            flags |= Flag.PADDING_HIGH;

        ByteBuffer header = generateHeader(FrameType.HEADERS, Frame.HEADER_LENGTH + extraPaddingBytes, (int)length, flags, streamId);

        if (extraPaddingBytes == 2)
            header.putShort((short)paddingLength);
        else if (extraPaddingBytes == 1)
            header.put((byte)paddingLength);

        ByteBufferPool.Lease lease = new ByteBufferPool.Lease(byteBufferPool);

        BufferUtil.flipToFlush(header, 0);
        lease.add(header, true);

        lease.merge(hpackBuffers);

        if (paddingBytes != null)
        {
            lease.add(ByteBuffer.wrap(paddingBytes), false);
        }

        return lease;
    }

    public ByteBufferPool.Lease generatePriority(int streamId, int dependentStreamId, int weight, boolean exclusive)
    {
        if (streamId < 0)
            throw new IllegalArgumentException("Invalid stream id: " + streamId);
        if (dependentStreamId < 0)
            throw new IllegalArgumentException("Invalid dependent stream id: " + dependentStreamId);

        ByteBufferPool.Lease lease = new ByteBufferPool.Lease(byteBufferPool);

        ByteBuffer header = generateHeader(FrameType.PRIORITY, 5, 0, dependentStreamId);

        if (exclusive)
            streamId |= 0x80_00_00_00;

        header.putInt(streamId);

        header.put((byte)weight);

        BufferUtil.flipToFlush(header, 0);
        lease.add(header, true);

        return lease;
    }

    public ByteBufferPool.Lease generateReset(int streamId, int error)
    {
        if (streamId < 0)
            throw new IllegalArgumentException("Invalid stream id: " + streamId);

        ByteBufferPool.Lease lease = new ByteBufferPool.Lease(byteBufferPool);

        ByteBuffer header = generateHeader(FrameType.RST_STREAM, 4, 0, streamId);

        header.putInt(error);

        BufferUtil.flipToFlush(header, 0);
        lease.add(header, true);

        return lease;
    }

    public ByteBufferPool.Lease generateSettings(Map<Integer, Integer> settings, boolean reply)
    {
        ByteBufferPool.Lease lease = new ByteBufferPool.Lease(byteBufferPool);

        ByteBuffer header = generateHeader(FrameType.SETTINGS, 5 * settings.size(), reply ? 0x01 : 0x00, 0);

        for (Map.Entry<Integer, Integer> entry : settings.entrySet())
        {
            header.put(entry.getKey().byteValue());
            header.putInt(entry.getValue());
        }

        BufferUtil.flipToFlush(header, 0);
        lease.add(header, true);

        return lease;
    }

    public ByteBufferPool.Lease generatePing(byte[] payload, boolean reply)
    {
        if (payload.length != 8)
            throw new IllegalArgumentException("Invalid payload length: " + payload.length);

        ByteBufferPool.Lease lease = new ByteBufferPool.Lease(byteBufferPool);

        ByteBuffer header = generateHeader(FrameType.PING, 8, reply ? 0x01 : 0x00, 0);

        header.put(payload);

        BufferUtil.flipToFlush(header, 0);
        lease.add(header, true);

        return lease;
    }

    public ByteBufferPool.Lease generateGoAway(int lastStreamId, int error, byte[] payload)
    {
        if (lastStreamId < 0)
            throw new IllegalArgumentException("Invalid last stream id: " + lastStreamId);

        ByteBufferPool.Lease lease = new ByteBufferPool.Lease(byteBufferPool);

        int length = 4 + 4 + (payload != null ? payload.length : 0);
        ByteBuffer header = generateHeader(FrameType.GO_AWAY, length, 0, 0);

        header.putInt(lastStreamId);
        header.putInt(error);

        if (payload != null)
        {
            header.put(payload);
        }

        BufferUtil.flipToFlush(header, 0);
        lease.add(header, true);

        return lease;
    }

    public ByteBufferPool.Lease generateWindowUpdate(int streamId, int windowUpdate)
    {
        if (streamId < 0)
            throw new IllegalArgumentException("Invalid stream id: " + streamId);
        if (windowUpdate < 0)
            throw new IllegalArgumentException("Invalid window update: " + windowUpdate);

        ByteBufferPool.Lease lease = new ByteBufferPool.Lease(byteBufferPool);

        ByteBuffer header = generateHeader(FrameType.WINDOW_UPDATE, 4, 0, streamId);

        header.putInt(windowUpdate);

        BufferUtil.flipToFlush(header, 0);
        lease.add(header, true);

        return lease;
    }

    private void generateData(ByteBufferPool.Lease lease, int streamId, ByteBuffer data, boolean last, boolean compress, int extraPaddingBytes, byte[] paddingBytes)
    {
        int paddingLength = paddingBytes == null ? 0 : paddingBytes.length;
        int length = extraPaddingBytes + data.remaining() + paddingLength;

        int flags = 0;
        if (last)
            flags |= Flag.END_STREAM;
        if (extraPaddingBytes > 0)
            flags |= Flag.PADDING_LOW;
        if (extraPaddingBytes > 1)
            flags |= Flag.PADDING_HIGH;
        if (compress)
            flags |= Flag.COMPRESS;

        ByteBuffer header = generateHeader(FrameType.DATA, Frame.HEADER_LENGTH + extraPaddingBytes, length, flags, streamId);

        if (extraPaddingBytes == 2)
            header.putShort((short)paddingLength);
        else if (extraPaddingBytes == 1)
            header.put((byte)paddingLength);

        BufferUtil.flipToFlush(header, 0);
        lease.add(header, true);

        lease.add(data, false);

        if (paddingBytes != null)
        {
            lease.add(ByteBuffer.wrap(paddingBytes), false);
        }
    }

    private ByteBuffer generateHeader(FrameType frameType, int length, int flags, int streamId)
    {
        return generateHeader(frameType, Frame.HEADER_LENGTH + length, length, flags, streamId);
    }

    private ByteBuffer generateHeader(FrameType frameType, int capacity, int length, int flags, int streamId)
    {
        ByteBuffer header = byteBufferPool.acquire(capacity, true);
        BufferUtil.clearToFill(header);

        header.putShort((short)length);
        header.put((byte)frameType.getType());
        header.put((byte)flags);
        header.putInt(streamId);

        return header;
    }
}
