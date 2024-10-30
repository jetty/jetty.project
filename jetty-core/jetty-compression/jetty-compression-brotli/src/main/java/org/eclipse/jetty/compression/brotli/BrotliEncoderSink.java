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
import java.util.concurrent.atomic.AtomicReference;

import com.aayushatharva.brotli4j.encoder.EncoderJNI;
import org.eclipse.jetty.compression.EncoderSink;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BrotliEncoderSink extends EncoderSink
{
    enum State
    {
        /**
         * Taking the input and encoding.
         */
        PROCESSING,
        /**
         * Done taking input, flushing whats left in encoder.
         */
        FLUSHING,
        /**
         * Done flushing, performing finish operation.
         */
        FINISHING,
        /**
         * Finish operation completed.
         */
        FINISHED;
    }

    private static final Logger LOG = LoggerFactory.getLogger(BrotliEncoderSink.class);
    private static final ByteBuffer EMPTY_BUFFER = ByteBuffer.allocate(0);
    private final BrotliCompression compression;
    private final EncoderJNI.Wrapper encoder;
    private final ByteBuffer inputBuffer;
    private final AtomicReference<State> state = new AtomicReference<State>(State.PROCESSING);

    public BrotliEncoderSink(BrotliCompression compression, Content.Sink sink, BrotliEncoderConfig config)
    {
        super(sink);
        this.compression = compression;
        try
        {
            this.encoder = new EncoderJNI.Wrapper(config.getBufferSize(), config.getCompressionLevel(), config.getLgWindow(), config.getMode());
            this.inputBuffer = encoder.getInputBuffer();
        }
        catch (IOException e)
        {
            throw new RuntimeException(e);
        }
    }

    @Override
    protected WriteRecord encode(boolean last, ByteBuffer content)
    {
        if (encoder.isFinished())
            return null;

        while (true)
        {
            switch (state.get())
            {
                case PROCESSING ->
                {
                    try
                    {
                        while (content.hasRemaining())
                        {
                            // only encode if inputBuffer is full.
                            if (!inputBuffer.hasRemaining())
                            {
                                ByteBuffer output = encode(EncoderJNI.Operation.PROCESS);
                                if (output != null)
                                    return new WriteRecord(false, output, Callback.NOOP);
                            }

                            // the only place the input buffer gets set.
                            int len = BufferUtil.put(content, inputBuffer);
                            // do not flip input buffer, that's not what Brotli4j expects/wants.
                        }
                        // content is fully consumed.
                        if (!last)
                            return null;
                    }
                    finally
                    {
                        if (last)
                            state.compareAndSet(State.PROCESSING, State.FLUSHING);
                    }
                }
                case FLUSHING ->
                {
                    inputBuffer.limit(inputBuffer.position());
                    ByteBuffer output = encode(EncoderJNI.Operation.FLUSH);
                    state.compareAndSet(State.FLUSHING, State.FINISHING);
                    if (output != null)
                        return new WriteRecord(false, output, Callback.NOOP);
                }
                case FINISHING ->
                {
                    inputBuffer.limit(inputBuffer.position());
                    ByteBuffer output = encode(EncoderJNI.Operation.FINISH);
                    state.compareAndSet(State.FINISHING, State.FINISHED);
                    return new WriteRecord(true, output != null ? output : EMPTY_BUFFER, Callback.NOOP);
                }
                case FINISHED ->
                {
                    return null;
                }
            }
        }
    }

    protected ByteBuffer encode(EncoderJNI.Operation op)
    {
        try
        {
            boolean inputPushed = false;
            ByteBuffer output = null;
            while (true)
            {
                if (!encoder.isSuccess())
                {
                    throw new IOException("Brotli Encoder failure");
                }
                // process previous output before new input
                else if (encoder.hasMoreOutput())
                {
                    output = encoder.pull();
                }
                else if (encoder.hasRemainingInput())
                {
                    encoder.push(op, 0);
                }
                else if (!inputPushed)
                {
                    encoder.push(op, inputBuffer.limit());
                    inputPushed = true;
                }
                else
                {
                    inputBuffer.clear();
                    return output;
                }
            }
        }
        catch (IOException e)
        {
            throw new RuntimeException(e);
        }
    }

    @Override
    protected void release()
    {
        this.encoder.destroy();
    }

    private State getState()
    {
        return state.get();
    }
}
