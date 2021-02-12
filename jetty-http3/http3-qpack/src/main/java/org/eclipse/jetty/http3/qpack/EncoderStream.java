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

public class EncoderStream
{
    private final OutputStream _outputStream;
    private final ByteBufferPool _bufferPool;

    public EncoderStream(OutputStream outputStream)
    {
        this (outputStream, new NullByteBufferPool());
    }

    public EncoderStream(OutputStream outputStream, ByteBufferPool bufferPool)
    {
        _outputStream = outputStream;
        _bufferPool = bufferPool;
    }

    private boolean shouldHuffmanEncode(String s)
    {
        return s.length() > 128;
    }

    void setCapacity(int capacity) throws IOException
    {
        int size = NBitInteger.octectsNeeded(5, capacity) + 1;
        ByteBuffer buffer = _bufferPool.acquire(size, false);
        BufferUtil.clearToFill(buffer);
        buffer.put((byte)0x20);
        NBitInteger.encode(buffer, 5, capacity);
        BufferUtil.flipToFlush(buffer, 0);
        BufferUtil.writeTo(buffer, _outputStream);
    }

    void insertEntry(String name, String value) throws IOException
    {
        boolean huffmanEncodeName = shouldHuffmanEncode(name);
        boolean huffmanEncodeValue = shouldHuffmanEncode(value);

        int size = (huffmanEncodeName ? Huffman.octetsNeeded(name) : name.length()) +
            (huffmanEncodeValue ? Huffman.octetsNeeded(value) : value.length()) + 2;
        ByteBuffer buffer = _bufferPool.acquire(size, false);
        BufferUtil.clearToFill(buffer);

        if (huffmanEncodeName)
        {
            buffer.put((byte)(0x40 | 0x20));
            NBitInteger.encode(buffer, 5, Huffman.octetsNeeded(name));
            Huffman.encode(buffer, name);
        }
        else
        {
            buffer.put((byte)(0x40));
            NBitInteger.encode(buffer, 5, name.length());
            buffer.put(name.getBytes());
        }

        if (huffmanEncodeValue)
        {
            buffer.put((byte)(0x80));
            NBitInteger.encode(buffer, 7, Huffman.octetsNeeded(value));
            Huffman.encode(buffer, value);
        }
        else
        {
            buffer.put((byte)(0x00));
            NBitInteger.encode(buffer, 5, value.length());
            buffer.put(value.getBytes());
        }

        BufferUtil.flipToFlush(buffer, 0);
        BufferUtil.writeTo(buffer, _outputStream);
    }

    void insertEntry(int nameRef, boolean dynamicTableReference, String value) throws IOException
    {
        boolean huffmanEncode = shouldHuffmanEncode(value);
        int size = NBitInteger.octectsNeeded(6, nameRef) + (huffmanEncode ? Huffman.octetsNeeded(value) : value.length()) + 2;
        ByteBuffer buffer = _bufferPool.acquire(size, false);
        BufferUtil.clearToFill(buffer);

        // First bit indicates the instruction, second bit is whether it is a dynamic table reference or not.
        buffer.put((byte)(0x80 | (dynamicTableReference ? 0x00 : 0x40)));
        NBitInteger.encode(buffer, 6, nameRef);

        // We will not huffman encode the string.
        if (huffmanEncode)
        {
            buffer.put((byte)(0x80));
            NBitInteger.encode(buffer, 7, Huffman.octetsNeeded(value));
            Huffman.encode(buffer, value);
        }
        else
        {
            buffer.put((byte)(0x00));
            NBitInteger.encode(buffer, 7, value.length());
            buffer.put(value.getBytes());
        }

        BufferUtil.flipToFlush(buffer, 0);
        BufferUtil.writeTo(buffer, _outputStream);
    }

    void insertEntry(int ref) throws IOException
    {
        int size = NBitInteger.octectsNeeded(5, ref) + 1;
        ByteBuffer buffer = _bufferPool.acquire(size, false);
        BufferUtil.clearToFill(buffer);
        buffer.put((byte)0x00);
        NBitInteger.encode(buffer, 5, ref);
        BufferUtil.flipToFlush(buffer, 0);
        BufferUtil.writeTo(buffer, _outputStream);
    }
}
