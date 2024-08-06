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

package org.eclipse.jetty.compression.zstandard;

import java.io.IOException;
import java.nio.ByteBuffer;

import com.github.luben.zstd.EndDirective;
import com.github.luben.zstd.ZstdCompressCtx;
import org.eclipse.jetty.compression.BufferQueue;
import org.eclipse.jetty.compression.Compression;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.util.BufferUtil;

public class ZstandardEncoder implements Compression.Encoder
{
    private static final ByteBuffer EMPTY_DIRECT_BUFFER = ByteBuffer.allocateDirect(0);
    private final ZstandardCompression compression;
    private final ZstdCompressCtx compressCtx;
    private final BufferQueue outputQueue;

    enum State
    {
        OPENED, // Encoder has been opened, but no input has yet been sent through it
        CONTINUE, // Input data has been submitted
        FINISHED, // Input data has finished, remaining compressed data is being flushed from zstd-jni internals
        CLOSED // Encoder is closed
    }

    private State state;

    public ZstandardEncoder(ZstandardCompression compression)
    {
        this.state = State.OPENED;
        this.compression = compression;
        this.compressCtx = new ZstdCompressCtx();
        this.compressCtx.setLevel(compression.getCompressionLevel());
        this.outputQueue = new BufferQueue(compression.getByteBufferPool());
    }

    @Override
    public void addInput(ByteBuffer content)
    {
        assert content.isDirect(); // requirement of zstd-jni

        if (state == State.CLOSED)
            throw new IllegalStateException("Encoder has been closed");
        if (state == State.FINISHED)
            throw new IllegalStateException("Input has already been finished");

        state = State.CONTINUE;

        // fully consume input buffer
        while (content.hasRemaining())
        {
            // Perform CONTINUE operation
            RetainableByteBuffer outputBuf = compression.acquireByteBuffer();
            outputBuf.getByteBuffer().clear();
            compressCtx.compressDirectByteBufferStream(outputBuf.getByteBuffer(), content, EndDirective.CONTINUE);
            outputBuf.getByteBuffer().flip();
            if (outputBuf.getByteBuffer().hasRemaining())
                outputQueue.add(outputBuf);
            else
                outputBuf.release();
        }
    }

    @Override
    public void finishInput()
    {
        if (state == State.CLOSED)
            throw new IllegalStateException("Encoder has been closed");

        state = State.FINISHED;

        // Trigger END operation
        RetainableByteBuffer outputBuf = compression.acquireByteBuffer();
        outputBuf.getByteBuffer().clear();
        this.compressCtx.compressDirectByteBufferStream(outputBuf.getByteBuffer(), EMPTY_DIRECT_BUFFER, EndDirective.END);
        outputBuf.getByteBuffer().flip();
        if (outputBuf.getByteBuffer().hasRemaining())
            outputQueue.add(outputBuf);
        else
            outputBuf.release();

        // handle FLUSH operation to get remaining bytes held in zstd-jni
        // this can happen if the last compress END (op) filled the output buffer
        // but there are still bytes that need to be put into outputQueue.
        boolean flushing = true;
        while (flushing)
        {
            outputBuf = compression.acquireByteBuffer();
            outputBuf.getByteBuffer().clear();
            this.compressCtx.compressDirectByteBufferStream(outputBuf.getByteBuffer(), EMPTY_DIRECT_BUFFER, EndDirective.FLUSH);
            outputBuf.getByteBuffer().flip();
            if (outputBuf.getByteBuffer().hasRemaining())
            {
                outputQueue.add(outputBuf);
            }
            else
            {
                flushing = false;
                outputBuf.release();
            }
        }
    }

    @Override
    public boolean isOutputFinished()
    {
        return switch (state)
        {
            case CLOSED ->
            {
                yield true;
            }
            case FINISHED ->
            {
                yield !outputQueue.hasRemaining();
            }
            default ->
            {
                yield false;
            }
        };
    }

    @Override
    public boolean needsInput()
    {
        return switch (state)
        {
            case FINISHED, CLOSED ->
            {
                yield false;
            }
            default ->
            {
                yield true;
            }
        };
    }

    @Override
    public int encode(ByteBuffer outputBuffer) throws IOException
    {
        if (state == State.CLOSED)
            throw new IllegalStateException("Encoder has been closed");

        ByteBuffer compressed = outputQueue.getBuffer();
        if (compressed == null)
            return 0;

        int len = 0;
        if (compressed.hasRemaining())
            len = BufferUtil.put(compressed, outputBuffer);
        return len;
    }

    @Override
    public void close()
    {
        outputQueue.close();
        state = State.CLOSED;
    }
}
