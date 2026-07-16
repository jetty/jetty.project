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

package org.eclipse.jetty.compression.brotli.internal;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicReference;

import com.aayushatharva.brotli4j.encoder.Encoder;
import com.aayushatharva.brotli4j.encoder.EncoderJNI;
import org.eclipse.jetty.compression.EncoderSink;
import org.eclipse.jetty.compression.brotli.BrotliEncoderConfig;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;

public class BrotliEncoderSink extends EncoderSink
{
    enum State
    {
        /**
         * Consuming input content, pushing it to the encoder each time the input buffer fills.
         */
        PROCESSING,
        /**
         * Draining the output of a {@link EncoderJNI.Operation#PROCESS} operation, one buffer at a time.
         */
        PROCESS_OUTPUT,
        /**
         * Draining the output of the final {@link EncoderJNI.Operation#FLUSH} operation.
         */
        FLUSH_OUTPUT,
        /**
         * Draining the output of the final {@link EncoderJNI.Operation#FINISH} operation.
         */
        FINISH_OUTPUT,
        /**
         * All output has been produced.
         */
        FINISHED
    }

    private final EncoderJNI.Wrapper encoder;
    private final ByteBuffer inputBuffer;
    private final AtomicReference<State> state = new AtomicReference<>(State.PROCESSING);

    public BrotliEncoderSink(Content.Sink sink, BrotliEncoderConfig config)
    {
        super(sink);
        try
        {
            Encoder.Mode mode = Encoder.Mode.of(config.getStrategy());
            this.encoder = new EncoderJNI.Wrapper(config.getBufferSize(), config.getCompressionLevel(), config.getLgWindow(), mode);
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
        // Guard on our own terminal state: a single operation is drained across several invocations, so
        // the encoder can report finished while we are still being re-invoked to emit its output. Only a
        // write once we have reached FINISHED is illegal.
        if (state.get() == State.FINISHED)
            throw new IllegalStateException("Already released");

        while (true)
        {
            switch (state.get())
            {
                case PROCESSING ->
                {
                    if (BufferUtil.hasContent(content))
                    {
                        if (inputBuffer.hasRemaining())
                        {
                            // Fill the input buffer; do not flip it, that's not what Brotli4j expects/wants.
                            BufferUtil.put(content, inputBuffer);
                        }
                        else
                        {
                            // Input buffer full: hand it to the encoder, then drain the produced output.
                            encoder.push(EncoderJNI.Operation.PROCESS, inputBuffer.limit());
                            state.set(State.PROCESS_OUTPUT);
                        }
                    }
                    else
                    {
                        // Content fully consumed. A non-last write simply waits for more content.
                        if (!last)
                            return null;
                        // Final write: flush whatever is still buffered, then finish.
                        inputBuffer.limit(inputBuffer.position());
                        encoder.push(EncoderJNI.Operation.FLUSH, inputBuffer.limit());
                        state.set(State.FLUSH_OUTPUT);
                    }
                }
                case PROCESS_OUTPUT ->
                {
                    ByteBuffer output = drain(EncoderJNI.Operation.PROCESS);
                    if (output != null)
                        return new WriteRecord(false, output, Callback.NOOP);
                    // Output drained: reuse the input buffer for the next content.
                    inputBuffer.clear();
                    state.set(State.PROCESSING);
                }
                case FLUSH_OUTPUT ->
                {
                    ByteBuffer output = drain(EncoderJNI.Operation.FLUSH);
                    if (output != null)
                        return new WriteRecord(false, output, Callback.NOOP);
                    // Flush drained: finish the stream (no further input).
                    encoder.push(EncoderJNI.Operation.FINISH, 0);
                    state.set(State.FINISH_OUTPUT);
                }
                case FINISH_OUTPUT ->
                {
                    ByteBuffer output = drain(EncoderJNI.Operation.FINISH);
                    if (output != null)
                        return new WriteRecord(false, output, Callback.NOOP);
                    // Finish drained: signal completion with a final empty last write.
                    state.set(State.FINISHED);
                    return new WriteRecord(true, BufferUtil.EMPTY_BUFFER, Callback.NOOP);
                }
                case FINISHED ->
                {
                    return null;
                }
            }
        }
    }

    /**
     * Returns the next output buffer produced by the given operation, or {@code null} once the operation
     * has been fully drained.
     *
     * <p>The operation's input must already have been submitted with
     * {@link EncoderJNI.Wrapper#push(EncoderJNI.Operation, int)}; this method only pulls output and, if the
     * encoder still holds unprocessed input, drives it with a zero-length push. A single operation
     * (notably the final {@code FLUSH}/{@code FINISH} of a large response) can produce several output
     * buffers, so this returns one buffer at a time and is invoked once per {@link EncoderSink} write.
     * Returning them individually is what prevents earlier buffers from being dropped and the response
     * from being truncated.</p>
     */
    private ByteBuffer drain(EncoderJNI.Operation op)
    {
        try
        {
            while (true)
            {
                if (!encoder.isSuccess())
                    throw new IOException("Brotli Encoder failure");

                if (encoder.hasMoreOutput())
                    return encoder.pull();

                if (encoder.hasRemainingInput())
                {
                    encoder.push(op, 0);
                    continue;
                }

                return null;
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
}
