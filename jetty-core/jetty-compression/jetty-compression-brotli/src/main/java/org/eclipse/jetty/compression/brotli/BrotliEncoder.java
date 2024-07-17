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

package org.eclipse.jetty.compression.brotli;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.WritableByteChannel;

import com.aayushatharva.brotli4j.encoder.BrotliEncoderChannel;
import com.aayushatharva.brotli4j.encoder.Encoder;
import com.aayushatharva.brotli4j.encoder.PreparedDictionary;
import org.eclipse.jetty.compression.Compression;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.io.RuntimeIOException;
import org.eclipse.jetty.util.BufferUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BrotliEncoder implements Compression.Encoder, WritableByteChannel
{
    private static final Logger LOG = LoggerFactory.getLogger(BrotliEncoder.class);

    private final ByteBufferPool bufferPool;
    private final int outputBufferSize;
    // TODO: change to com.aayushatharva.brotli4j.encoder.EncoderJNI.Wrapper once new release
    // of brotli4j is available with fix https://github.com/hyperxpro/Brotli4j/issues/144
    private final BrotliEncoderChannel encoder;
    private ByteBuffer inputBuffer;

    public BrotliEncoder(BrotliCompression brotliCompression, ByteBufferPool pool, int outputBufferSize)
    {
        this.bufferPool = pool;
        this.outputBufferSize = brotliCompression.getBufferSize();
        try
        {
            Encoder.Parameters params = brotliCompression.getEncoderParams();
            this.encoder = new BrotliEncoderChannel(this, params, brotliCompression.getBufferSize());
        }
        catch (IOException e)
        {
            throw new RuntimeException(e);
        }
    }

    public void attachDictionary(PreparedDictionary dictionary) throws IOException
    {
        encoder.attachDictionary(dictionary);
    }

    @Override
    public void begin()
    {
        // no header blocks in brotli
    }

    @Override
    public boolean isOpen()
    {
        return false;
    }

    @Override
    public void close() throws IOException
    {
        encoder.close();
    }

    @Override
    public void setInput(ByteBuffer content)
    {
        try
        {
            encoder.write(content);
        }
        catch (IOException e)
        {
            throw new RuntimeIOException(e);
        }
    }

    @Override
    public void finishInput()
    {
    }

    @Override
    public ByteOrder getByteOrder()
    {
        return ByteOrder.BIG_ENDIAN;
    }

    @Override
    public boolean isOutputFinished()
    {
        return encoder.isOpen();
    }

    @Override
    public boolean needsInput()
    {
        return encoder.isOpen();
    }

    @Override
    public int encode(ByteBuffer outputBuffer) throws IOException
    {
        return encoder.write(outputBuffer);
    }

    @Override
    public int getTrailerSize()
    {
        return 0;
    }

    @Override
    public RetainableByteBuffer acquireInitialOutputBuffer()
    {
        RetainableByteBuffer buffer = bufferPool.acquire(outputBufferSize, false);
        ByteBuffer byteBuffer = buffer.getByteBuffer();
        BufferUtil.flipToFill(byteBuffer);
        return buffer;
    }

    @Override
    public void addTrailer(ByteBuffer outputBuffer)
    {
        // no trailers in brotli
    }

    @Override
    public void release()
    {
        try
        {
            encoder.close();
        }
        catch (IOException e)
        {
            throw new RuntimeIOException(e);
        }
    }

    @Override
    public int write(ByteBuffer src) throws IOException
    {
        int pos = src.position();
        inputBuffer.put(src);
        return src.position() - pos;
    }
}
