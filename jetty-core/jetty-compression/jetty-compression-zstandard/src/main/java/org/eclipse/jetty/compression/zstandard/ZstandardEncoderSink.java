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

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicReference;

import com.github.luben.zstd.EndDirective;
import com.github.luben.zstd.ZstdCompressCtx;
import com.github.luben.zstd.ZstdFrameProgression;
import org.eclipse.jetty.compression.EncoderSink;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.util.Callback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ZstandardEncoderSink extends EncoderSink
{
    private static final Logger LOG = LoggerFactory.getLogger(ZstandardEncoderSink.class);
    /**
     * zstd-jni MUST have direct buffers.
     */
    private static final ByteBuffer EMPTY_DIRECT_BUFFER = ByteBuffer.allocateDirect(0);

    private enum State
    {
        CONTINUE,
        END,
        FLUSH,
        FINISHED
    }

    private final ZstandardCompression compression;
    private final ZstdCompressCtx compressCtx;
    private final int bufferSize;
    private final AtomicReference<State> state = new AtomicReference<>(State.CONTINUE);

    public ZstandardEncoderSink(ZstandardCompression compression, Content.Sink sink, ZstandardEncoderConfig config)
    {
        super(sink);
        this.compression = compression;
        this.bufferSize = config.getBufferSize();
        this.compressCtx = new ZstdCompressCtx();
        this.compressCtx.setLevel(config.getCompressionLevel());
        if (config.getStrategy() >= 0)
            this.compressCtx.setStrategy(config.getStrategy());
        this.compressCtx.setMagicless(config.isMagicless());
        this.compressCtx.setChecksum(config.isChecksum());
    }

    @Override
    protected boolean canEncode(boolean last, ByteBuffer content)
    {
        if (!content.hasRemaining())
        {
            // skip if progress not yet started.
            // this allows for empty body contents to not cause errors.
            ZstdFrameProgression frameProgression = compressCtx.getFrameProgression();
            if (frameProgression.getConsumed() <= 0)
            {
                return false;
            }
        }

        // Requirement of zstd-jni
        if (!content.isDirect())
            throw new IllegalArgumentException("ByteBuffer must be direct for zstd-jni");

        return true;
    }

    @Override
    protected WriteRecord encode(boolean last, ByteBuffer content)
    {
        State initialState = state.get();
        if (initialState == State.FINISHED)
            return null;

        boolean done = false;
        WriteRecord writeRecord = null;
        while (!done)
        {
            State state = this.state.get();
            writeRecord = switch (state)
            {
                case CONTINUE -> continueOp(last, content);
                case END -> endOp(last);
                case FLUSH -> flushOp(last);
                case FINISHED -> finishOp(last);
            };
            if (writeRecord != null)
                done = true;
            else if (!last && !content.hasRemaining())
                done = true;
        }
        if (LOG.isDebugEnabled())
            LOG.debug("encode() stateIn={}, last={}, content={}, write={}, stateNow={}",
                initialState, last, content, writeRecord, state);
        return writeRecord;
    }

    private WriteRecord continueOp(boolean last, ByteBuffer content)
    {
        RetainableByteBuffer outputBuf = compression.acquireByteBuffer(bufferSize);

        // process content (input) buffer using zstd-jni CONTINUE directive
        while (content.hasRemaining())
        {
            outputBuf.getByteBuffer().clear();
            boolean flushed = compressCtx.compressDirectByteBufferStream(outputBuf.getByteBuffer(), content, EndDirective.CONTINUE);
            outputBuf.getByteBuffer().flip();
            if (outputBuf.getByteBuffer().hasRemaining())
            {
                Callback writeCallback = Callback.from(outputBuf::release);
                return new WriteRecord(false, outputBuf.getByteBuffer(), writeCallback);
            }
        }
        outputBuf.release();
        if (last)
            state.compareAndSet(State.CONTINUE, State.END);
        return null;
    }

    private WriteRecord endOp(boolean last)
    {
        if (!last)
            throw new IllegalStateException("Directive.END not possible on non-last encode");


        state.compareAndSet(State.END, State.FLUSH);
        RetainableByteBuffer outputBuf = compression.acquireByteBuffer(bufferSize);
        // use zstd-jni END directive once.
        outputBuf.getByteBuffer().clear();
        // only run END compress once
        this.compressCtx.compressDirectByteBufferStream(outputBuf.getByteBuffer(), EMPTY_DIRECT_BUFFER, EndDirective.END);
        outputBuf.getByteBuffer().flip();
        if (outputBuf.getByteBuffer().hasRemaining())
        {
            Callback writeCallback = Callback.from(outputBuf::release);
            return new WriteRecord(false, outputBuf.getByteBuffer(), writeCallback);
        }
        outputBuf.release();
        return null;
    }

    private WriteRecord flushOp(boolean last)
    {
        if (!last)
            throw new IllegalStateException("Directive.END not possible on non-last encode");

        RetainableByteBuffer outputBuf = compression.acquireByteBuffer(bufferSize);
        // use zstd-jni FLUSH directive to flush remaining compressed bytes out
        // of the internal zstd buffers.
        outputBuf.getByteBuffer().clear();
        boolean actualLast = this.compressCtx.compressDirectByteBufferStream(outputBuf.getByteBuffer(), EMPTY_DIRECT_BUFFER, EndDirective.FLUSH);
        outputBuf.getByteBuffer().flip();
        if (actualLast || outputBuf.getByteBuffer().hasRemaining())
        {
            if (actualLast)
                state.compareAndSet(State.FLUSH, State.FINISHED);
            Callback writeCallback = Callback.from(outputBuf::release);
            return new WriteRecord(actualLast, outputBuf.getByteBuffer(), writeCallback);
        }

        outputBuf.release();
        return null;
    }

    private WriteRecord finishOp(boolean last)
    {
        // do nothing
        return null;
    }
}
