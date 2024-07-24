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
import java.nio.channels.ClosedChannelException;
import java.nio.channels.WritableByteChannel;
import java.util.ArrayDeque;
import java.util.Queue;

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

public class BrotliEncoder implements Compression.Encoder
{
    private static final Logger LOG = LoggerFactory.getLogger(BrotliEncoder.class);

    private final CaptureByteChannel captureChannel;
    // TODO: change to com.aayushatharva.brotli4j.encoder.EncoderJNI.Wrapper once new release is available for
    // https://github.com/hyperxpro/Brotli4j/issues/144
    private final BrotliEncoderChannel encoder;
    private ByteBuffer inputBuffer;

    public BrotliEncoder(BrotliCompression brotliCompression)
    {
        try
        {
            this.captureChannel = new CaptureByteChannel(brotliCompression.getByteBufferPool());
            Encoder.Parameters params = brotliCompression.getEncoderParams();
            this.encoder = new BrotliEncoderChannel(captureChannel, params);
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
    public void addInput(ByteBuffer content)
    {
        try
        {
            if (LOG.isDebugEnabled())
                LOG.debug("setInput - {}", BufferUtil.toDetailString(content));
            encoder.write(content);
            // Content should be fully consumed
            assert !content.hasRemaining();
        }
        catch (IOException e)
        {
            throw new RuntimeIOException(e);
        }
    }

    @Override
    public void finishInput()
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
    public boolean isOutputFinished()
    {
        if (this.captureChannel.isOpen())
            return false;

        if (this.captureChannel.hasOutput())
            return false;

        return true;
    }

    @Override
    public boolean needsInput()
    {
        return encoder.isOpen();
    }

    @Override
    public int encode(ByteBuffer outputBuffer) throws IOException
    {
        if (LOG.isDebugEnabled())
            LOG.debug("encode:1 - outputBuffer={}", BufferUtil.toDetailString(outputBuffer));
        ByteBuffer compressed = this.captureChannel.getBuffer();
        if (compressed == null)
            return 0;

        if (LOG.isDebugEnabled())
            LOG.debug("encode:2 - compressed={}", BufferUtil.toDetailString(compressed));
        int len = 0;
        if (compressed.hasRemaining())
            len = BufferUtil.put(compressed, outputBuffer);
        if (LOG.isDebugEnabled())
            LOG.debug("encode:3 - outputBuffer={}", BufferUtil.toDetailString(outputBuffer));
        return len;
    }

    @Override
    public int getTrailerSize()
    {
        // no trailers for brotli
        return 0;
    }

    @Override
    public void addTrailer(ByteBuffer outputBuffer)
    {
        // no trailers in brotli
    }

    @Override
    public void close() throws Exception
    {
        captureChannel.release();
        encoder.close();
    }

    private static class CaptureByteChannel implements WritableByteChannel
    {
        private final ByteBufferPool byteBufferPool;
        private final Queue<RetainableByteBuffer> bufferQueue = new ArrayDeque<>();
        private RetainableByteBuffer activeBuffer;
        private boolean closed = false;

        public CaptureByteChannel(ByteBufferPool byteBufferPool)
        {
            this.byteBufferPool = byteBufferPool;
        }

        public boolean hasOutput()
        {
            if (activeBuffer != null && activeBuffer.hasRemaining())
                return true;

            return !bufferQueue.isEmpty();
        }

        public ByteBuffer getBuffer()
        {
            if (activeBuffer != null && !activeBuffer.hasRemaining())
            {
                activeBuffer.release();
                activeBuffer = null;
            }

            if (activeBuffer == null)
                activeBuffer = bufferQueue.poll();

            if (activeBuffer != null)
                return activeBuffer.getByteBuffer();
            return null;
        }

        @Override
        public boolean isOpen()
        {
            return !closed;
        }

        public void release()
        {
            if (activeBuffer != null)
                activeBuffer.release();
            bufferQueue.forEach(RetainableByteBuffer::release);
        }

        @Override
        public void close() throws IOException
        {
            closed = true;
        }

        @Override
        public int write(ByteBuffer src) throws IOException
        {
            if (!isOpen())
                throw new ClosedChannelException();

            if (LOG.isDebugEnabled())
                LOG.debug("captured.write({})", BufferUtil.toDetailString(src));

            int len = src.remaining();
            RetainableByteBuffer copy = copyOf(src);
            if (LOG.isDebugEnabled())
                LOG.debug("capture.write() queue:{}", BufferUtil.toDetailString(copy.getByteBuffer()));
            bufferQueue.add(copy);
            return len;
        }

        private RetainableByteBuffer copyOf(ByteBuffer buf)
        {
            if (buf == null)
                return null;
            RetainableByteBuffer.Mutable copy = byteBufferPool.acquire(buf.remaining(), buf.isDirect());
            copy.getByteBuffer().clear();
            copy.getByteBuffer().put(buf);
            copy.getByteBuffer().flip();
            return copy;
        }
    }
}
