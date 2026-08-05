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

package org.eclipse.jetty.compression.gzip.internal;

import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

import org.eclipse.jetty.compression.EncoderSink;
import org.eclipse.jetty.compression.gzip.GzipCompression;
import org.eclipse.jetty.compression.gzip.GzipEncoderConfig;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Retainable;
import org.eclipse.jetty.util.buffer.RetainableByteBuffer;
import org.eclipse.jetty.util.compression.CompressionPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GzipEncoderSink extends EncoderSink
{
    enum State
    {
        /**
         * Need to write Headers
         */
        HEADERS,
        /**
         * Processing Body / Data.
         */
        BODY,
        /**
         * Input is complete, flushing the Gzip internals.
         */
        FLUSHING,
        /**
         * Processing Trailers
         */
        TRAILERS,
        /**
         * Processing is finished.
         */
        FINISHED
    }

    private static final Logger LOG = LoggerFactory.getLogger(GzipEncoderSink.class);
    /**
     * Per RFC-1952 (Section 2.3.1) this is the "Unknown" OS value as a byte.
     */
    private static final byte OS_UNKNOWN = (byte)0xFF;
    /**
     * The static Gzip Header
     */
    private static final byte[] GZIP_HEADER = new byte[]{
        (byte)0x1f, // Gzip Magic number (0x8B1F) [short]
        (byte)0x8b, // Gzip Magic number (0x8B1F) [short]
        Deflater.DEFLATED, // compression method
        0, // flags
        0, // modification time [int]
        0, // modification time [int]
        0, // modification time [int]
        0, // modification time [int]
        0, // extra flags
        OS_UNKNOWN // operating system
    };
    private final GzipCompression compression;
    private final CompressionPool<Deflater>.Entry deflaterEntry;
    private final Deflater deflater;
    private final int bufferSize;
    private final int flushMode;
    private final CRC32 crc = new CRC32();
    private final AtomicReference<State> state = new AtomicReference<>(State.HEADERS);
    private RetainableByteBuffer inputBuffer;
    private boolean released;

    public GzipEncoderSink(GzipCompression compression, Content.Sink sink, GzipEncoderConfig config)
    {
        super(sink);
        this.compression = compression;
        this.deflaterEntry = compression.getDeflaterPool().acquire();
        this.deflater = deflaterEntry.get();
        this.bufferSize = config.getBufferSize();
        this.deflater.reset();
        this.deflater.setStrategy(config.getStrategy());
        this.deflater.setLevel(config.getCompressionLevel());
        this.flushMode = config.isSyncFlush() ? Deflater.SYNC_FLUSH : Deflater.NO_FLUSH;
        this.crc.reset();
    }

    protected void addInput(RetainableByteBuffer content)
    {
        content.retain();
        inputBuffer = content;

        content.quietWriteTo(buffer ->
        {
            int position = buffer.position();
            crc.update(buffer);
            buffer.position(position);

            deflater.setInput(buffer);

            return 0;
        });
    }

    private void clearInput()
    {
        inputBuffer = Retainable.dispose(inputBuffer);
        deflater.setInput(BufferUtil.EMPTY_BUFFER);
    }

    @Override
    protected WriteRecord encode(boolean last, RetainableByteBuffer content)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("encode() state={}, last={}, content={}", state, last, content);

        if (released)
            throw new IllegalStateException("Already released");

        RetainableByteBuffer.Mutable output = null;
        try
        {
            while (true)
            {
                switch (state.get())
                {
                    case HEADERS ->
                    {
                        state.compareAndSet(State.HEADERS, State.BODY);
                        return new WriteRecord(false, RetainableByteBuffer.wrap(GZIP_HEADER));
                    }
                    case BODY ->
                    {
                        // Processing input
                        if (content != null && content.hasRemaining())
                        {
                            if (output == null)
                                output = compression.acquireBuffer(bufferSize);
                            if (encode(content, output))
                            {
                                WriteRecord writeRecord = new WriteRecord(false, output);
                                output = null;
                                return writeRecord;
                            }
                        }
                        else
                        {
                            clearInput();
                            if (last)
                            {
                                state.compareAndSet(State.BODY, State.FLUSHING);
                                deflater.finish();
                            }
                            else
                            {
                                return null;
                            }
                        }
                    }
                    case FLUSHING ->
                    {
                        // flush anything left out of the deflater
                        if (output == null)
                            output = compression.acquireBuffer(bufferSize);
                        if (!flush(output))
                            state.compareAndSet(State.FLUSHING, State.TRAILERS);
                        if (output.hasRemaining())
                        {
                            WriteRecord writeRecord = new WriteRecord(false, output);
                            output = null;
                            return writeRecord;
                        }
                    }
                    case TRAILERS ->
                    {
                        if (output == null)
                            output = compression.acquireBuffer(16);
                        trailers(output);
                        state.compareAndSet(State.TRAILERS, State.FINISHED);
                        WriteRecord writeRecord = new WriteRecord(true, output);
                        output = null;
                        return writeRecord;
                    }
                    case FINISHED ->
                    {
                        return null;
                    }
                }
            }
        }
        finally
        {
            if (output != null)
                output.release();
        }
    }

    @Override
    protected void release()
    {
        if (released)
            return;
        released = true;
        clearInput();
        deflaterEntry.release();
    }

    /**
     * Encode the content, put output into output buffer.
     *
     * @param content the input (uncompressed) content.
     * @param outputBuffer the output (compressed).
     * @return true if output was produced, false otherwise
     */
    private boolean encode(RetainableByteBuffer content, RetainableByteBuffer.Mutable outputBuffer)
    {
        if (deflater.needsInput())
            addInput(content);
        long encoded = outputBuffer.quietReadFrom(output -> deflater.deflate(output, flushMode));
        return encoded > 0L;
    }

    /**
     * Flush the Gzip internals.
     *
     * @param outputBuffer the output buffer to write to.
     * @return true if flush produced output, false to indicate no output produced.
     */
    private boolean flush(RetainableByteBuffer.Mutable outputBuffer)
    {
        while (!deflater.finished())
        {
            if (outputBuffer.quietReadFrom(output -> deflater.deflate(output, flushMode)) > 0L)
                return true;
        }
        return false;
    }

    private void trailers(RetainableByteBuffer.Mutable outputBuffer)
    {
        // CRC-32 of uncompressed data.
        outputBuffer.putInt((int)crc.getValue());
        // Number of uncompressed bytes.
        outputBuffer.putInt((int)deflater.getBytesRead());
    }
}
