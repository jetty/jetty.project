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

package org.eclipse.jetty.compression.zstandard.internal;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.ref.Cleaner;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicReference;

import com.github.luben.zstd.EndDirective;
import com.github.luben.zstd.ZstdCompressCtx;
import org.eclipse.jetty.compression.EncoderSink;
import org.eclipse.jetty.compression.zstandard.ZstandardCompression;
import org.eclipse.jetty.compression.zstandard.ZstandardEncoderConfig;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.buffer.ReadableBuffer;
import org.eclipse.jetty.util.buffer.WritableBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ZstandardEncoderSink extends EncoderSink
{
    private enum State
    {
        CONTINUE,
        END,
        FLUSH,
        FINISHED
    }

    private static final Logger LOG = LoggerFactory.getLogger(ZstandardEncoderSink.class);
    /**
     * zstd-jni MUST have direct buffers.
     */
    private static final ByteBuffer EMPTY_DIRECT_BUFFER = ByteBuffer.allocateDirect(0);
    private final ZstandardCompression compression;
    private final ZstdCompressCtx compressCtx;
    private final int bufferSize;
    private final AtomicReference<State> state = new AtomicReference<>(State.CONTINUE);
    private final Cleaner.Cleanable cleanable;

    public ZstandardEncoderSink(ZstandardCompression compression, Content.Sink sink, ZstandardEncoderConfig config)
    {
        super(sink);
        this.compression = compression;
        this.bufferSize = config.getBufferSize();
        this.compressCtx = new ZstdCompressCtx();
        this.cleanable = compression.getCleaner().register(this, compressCtx::close);
        this.compressCtx.setLevel(config.getCompressionLevel());
        if (config.getStrategy() >= 0)
            this.compressCtx.setStrategy(config.getStrategy());
        this.compressCtx.setMagicless(config.isMagicless());
        this.compressCtx.setChecksum(config.isChecksum());
    }

    @Override
    protected void release()
    {
        cleanable.clean();
    }

    @Override
    protected WriteRecord encode(boolean last, ReadableBuffer content)
    {
        State initialState = state.get();
        if (initialState == State.FINISHED)
            throw new IllegalStateException("Already released");

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
                case FINISHED -> null;
            };
            if (writeRecord != null)
                done = true;
            else if (!last && content != null && content.remaining() == 0L)
                done = true;
        }
        if (LOG.isDebugEnabled())
            LOG.debug("encode() stateIn={}, last={}, content={}, write={}, stateNow={}",
                initialState, last, content, writeRecord, state);
        return writeRecord;
    }

    protected ReadableBuffer ensureDirect(ReadableBuffer buffer, int size)
    {
        if (buffer == null || buffer.remaining() == 0L)
            return ReadableBuffer.EMPTY;
        try
        {
            ReadableBuffer[] result = new ReadableBuffer[1];
            buffer.writeTo(input ->
            {
                if (input.isDirect())
                {
                    result[0] = buffer.slice(buffer.position(), Math.min(input.remaining(), size));
                    buffer.position(buffer.position() + result[0].remaining());
                }
                else
                {
                    WritableBuffer direct = compression.acquireBuffer(size);
                    BufferUtil.put(input, direct);
                    result[0] = direct.toReadable();
                }
            });
            return result[0];
        }
        catch (IOException e)
        {
            throw new UncheckedIOException(e);
        }
    }

    private WriteRecord continueOp(boolean last, ReadableBuffer content)
    {
        WritableBuffer outputBuf = compression.acquireBuffer(bufferSize);

        // process content (input) buffer using zstd-jni CONTINUE directive
        try
        {
            while (content.remaining() > 0L)
            {
                // content must be a direct bytebuffer, and we have to assume that the size
                // of the content buffer can be huge (multi megabyte or bigger), so lets
                // process the content one limited direct buffer at a time.
                ReadableBuffer inputBuf = ensureDirect(content, bufferSize);
                try
                {
                    while (inputBuf.remaining() > 0L)
                    {
                        try
                        {
                            WritableBuffer wb = outputBuf;
                            inputBuf.writeTo(input ->
                            {
                                wb.readFrom(output ->
                                {
                                    compressCtx.compressDirectByteBufferStream(output, input, EndDirective.CONTINUE);
                                    return false;
                                });
                            });
                        }
                        catch (IOException e)
                        {
                            throw new UncheckedIOException(e);
                        }

                        if (outputBuf.position() > 0L)
                        {
                            if (inputBuf.remaining() > 0L)
                                content.position(content.position() - inputBuf.remaining());

                            WriteRecord writeRecord = new WriteRecord(false, outputBuf.toReadable());
                            outputBuf = null;
                            return writeRecord;
                        }
                    }
                }
                finally
                {
                    inputBuf.release();
                }
            }
        }
        finally
        {
            if (outputBuf != null)
                outputBuf.release();
        }

        if (last)
            state.compareAndSet(State.CONTINUE, State.END);
        return null;
    }

    private WriteRecord endOp(boolean last)
    {
        if (!last)
            throw new IllegalStateException("Directive.END not possible on non-last encode");

        state.compareAndSet(State.END, State.FLUSH);
        WritableBuffer outputBuf = compression.acquireBuffer(bufferSize);
        try
        {
            // use zstd-jni END directive once.
            // only run END compress once
            outputBuf.readFrom(output ->
            {
                compressCtx.compressDirectByteBufferStream(output, EMPTY_DIRECT_BUFFER, EndDirective.END);
                return false;
            });
        }
        catch (IOException e)
        {
            outputBuf.release();
            throw new UncheckedIOException(e);
        }
        ReadableBuffer rb = outputBuf.toReadable();
        if (rb.remaining() > 0L)
            return new WriteRecord(false, rb);
        outputBuf.release();
        return null;
    }

    private WriteRecord flushOp(boolean last)
    {
        if (!last)
            throw new IllegalStateException("Directive.END not possible on non-last encode");

        WritableBuffer outputBuf = compression.acquireBuffer(bufferSize);
        try
        {
            // use zstd-jni FLUSH directive to flush remaining compressed bytes out
            // of the internal zstd buffers.
            boolean actualLast = outputBuf.readFrom(output ->
            {
                return compressCtx.compressDirectByteBufferStream(output, EMPTY_DIRECT_BUFFER, EndDirective.FLUSH);
            }) == -1;
            ReadableBuffer rb = outputBuf.toReadable();
            if (actualLast || rb.remaining() > 0L)
            {
                if (actualLast)
                    state.compareAndSet(State.FLUSH, State.FINISHED);
                return new WriteRecord(actualLast, rb);
            }
        }
        catch (IOException e)
        {
            outputBuf.release();
            throw new UncheckedIOException(e);
        }

        outputBuf.release();
        return null;
    }
}
