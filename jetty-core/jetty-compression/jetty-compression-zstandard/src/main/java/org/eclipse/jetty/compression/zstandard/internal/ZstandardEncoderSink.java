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

import java.lang.ref.Cleaner;
import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

import com.github.luben.zstd.EndDirective;
import com.github.luben.zstd.ZstdCompressCtx;
import org.eclipse.jetty.compression.EncoderSink;
import org.eclipse.jetty.compression.zstandard.ZstandardCompression;
import org.eclipse.jetty.compression.zstandard.ZstandardEncoderConfig;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.util.buffer.RetainableByteBuffer;
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
    protected WriteRecord encode(boolean last, RetainableByteBuffer content)
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
                case CONTINUE -> continueOp(last, Objects.requireNonNullElse(content, RetainableByteBuffer.empty()));
                case END -> endOp(last);
                case FLUSH -> flushOp(last);
                case FINISHED -> null;
            };
            if (writeRecord != null)
                done = true;
            else if (!last && content != null && !content.hasRemaining())
                done = true;
        }
        if (LOG.isDebugEnabled())
            LOG.debug("encode() stateIn={}, last={}, content={}, write={}, stateNow={}",
                initialState, last, content, writeRecord, state);
        return writeRecord;
    }

    private WriteRecord continueOp(boolean last, RetainableByteBuffer content)
    {
        // Process content (input) buffer using zstd-jni CONTINUE directive.
        try (RetainableByteBuffer.Mutable outputBuffer = compression.acquireBuffer(bufferSize))
        {
            while (content.hasRemaining())
            {
                // The content must be a direct bytebuffer, and we have to assume that
                // the size of the content buffer can be huge (multi megabyte or bigger),
                // so let's process the content one limited direct buffer at a time.
                content.quietRead(input ->
                {
                    int remaining = input.remaining();

                    ByteBuffer inputBB;
                    RetainableByteBuffer inputBuffer;
                    if (input.isDirect())
                    {
                        int length = Math.min(input.remaining(), bufferSize);
                        inputBB = input.slice(input.position(), length);
                        inputBuffer = null;
                    }
                    else
                    {
                        RetainableByteBuffer.Mutable direct = compression.acquireBuffer(bufferSize);
                        int length = Math.min(input.remaining(), bufferSize);
                        direct.append(input.slice(input.position(), length));
                        ByteBuffer[] result = new ByteBuffer[1];
                        direct.quietRead(i ->
                        {
                            result[0] = i;
                            return 0;
                        });
                        inputBB = result[0];
                        inputBuffer = direct;
                    }

                    try (inputBuffer)
                    {
                        while (inputBB.hasRemaining())
                        {
                            int r = inputBB.remaining();
                            outputBuffer.write(outputBB ->
                            {
                                int p = outputBB.position();
                                compressCtx.compressDirectByteBufferStream(outputBB, inputBB, EndDirective.CONTINUE);
                                return outputBB.position() - p;
                            });
                            int consumed = r - inputBB.remaining();
                            input.position(input.position() + consumed);

                            if (outputBuffer.space() == 0)
                                break;
                        }

                        return remaining - input.remaining();
                    }
                });

                if (outputBuffer.hasRemaining())
                    return new WriteRecord(false, outputBuffer);
            }

            if (last)
                state.compareAndSet(State.CONTINUE, State.END);
            return null;
        }
    }

    private WriteRecord endOp(boolean last)
    {
        if (!last)
            throw new IllegalStateException("Directive.END not possible on non-last encode");

        state.compareAndSet(State.END, State.FLUSH);
        try (RetainableByteBuffer.Mutable outputBuffer = compression.acquireBuffer(bufferSize))
        {
            // use zstd-jni END directive once.
            // only run END compress once
            outputBuffer.quietWrite(output ->
            {
                int p = output.position();
                compressCtx.compressDirectByteBufferStream(output, EMPTY_DIRECT_BUFFER, EndDirective.END);
                return output.position() - p;
            });
            if (outputBuffer.hasRemaining())
                return new WriteRecord(false, outputBuffer);
            return null;
        }
    }

    private WriteRecord flushOp(boolean last)
    {
        if (!last)
            throw new IllegalStateException("Directive.END not possible on non-last encode");

        try (RetainableByteBuffer.Mutable outputBuffer = compression.acquireBuffer(bufferSize))
        {
            // use zstd-jni FLUSH directive to flush remaining compressed bytes out
            // of the internal zstd buffers.
            boolean[] result = new boolean[1];
            outputBuffer.quietWrite(output ->
            {
                int p = output.position();
                result[0] = compressCtx.compressDirectByteBufferStream(output, EMPTY_DIRECT_BUFFER, EndDirective.FLUSH);
                return output.position() - p;
            });
            boolean actualLast = result[0];
            if (actualLast || outputBuffer.hasRemaining())
            {
                if (actualLast)
                    state.compareAndSet(State.FLUSH, State.FINISHED);
                return new WriteRecord(actualLast, outputBuffer);
            }
            return null;
        }
    }
}
