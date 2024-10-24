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

package org.eclipse.jetty.compression.gzip;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

import org.eclipse.jetty.compression.EncoderSink;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
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
    private static final ByteBuffer EMPTY_BUFFER = ByteBuffer.allocate(0);
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
    /**
     * Per RFC-1952, the GZIP trailer is 8 bytes.
     * 1. [CRC32] integer (4 bytes) representing CRC of uncompressed data.
     * 2. [ISIZE] integer (4 bytes) representing total bytes of uncompressed data.
     * This implies that Gzip cannot properly handle uncompressed sizes above <em>2^32 bytes</em> (or <em>4,294,967,296 bytes</em>)
     */
    private static final int GZIP_TRAILER_SIZE = 8;
    private final GzipCompression compression;
    private final CompressionPool<Deflater>.Entry deflaterEntry;
    private final Deflater deflater;
    private final RetainableByteBuffer inputBuffer;
    private final ByteBuffer input;
    private final int bufferSize;
    private final CRC32 crc = new CRC32();
    private final AtomicReference<State> state = new AtomicReference<State>(State.HEADERS);
    /**
     * Number of input bytes provided to the deflater.
     * This is different then {@link Deflater#getTotalIn()} as that only shows
     * the number of input bytes that have been read.
     */
    private long inputBytesProvided = 0;

    public GzipEncoderSink(GzipCompression compression, Content.Sink sink, GzipEncoderConfig config)
    {
        super(sink);
        this.compression = compression;
        this.deflaterEntry = compression.getDeflaterPool().acquire();
        this.deflater = deflaterEntry.get();
        this.bufferSize = config.getBufferSize();
        this.inputBuffer = compression.acquireByteBuffer(bufferSize);
        this.input = this.inputBuffer.getByteBuffer();
        this.input.position(this.input.limit()); // set to totally consume at first
        this.deflater.reset();
        this.deflater.setInput(input);
        this.deflater.setStrategy(config.getStrategy());
        this.deflater.setLevel(config.getCompressionLevel());
        this.crc.reset();
    }

    protected void addInput(ByteBuffer content)
    {
        int pos = BufferUtil.flipToFill(input);
        int space = Math.min(input.remaining(), content.remaining());
        ByteBuffer slice = content.slice();
        slice.limit(space);
        inputBytesProvided += slice.remaining();
        // Update CRC based on what can be consumed right now.
        // Any leftover content will be consumed on a later call.
        crc.update(slice.slice());
        input.put(slice);
        BufferUtil.flipToFlush(input, pos);
        // consume the bytes on content
        content.position(content.position() + space);
    }

    @Override
    protected WriteRecord encode(boolean last, ByteBuffer content)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("encode() last={}, content={}", last, BufferUtil.toDetailString(content));

        RetainableByteBuffer output = null;
        try
        {
            while (true)
            {
                switch (state.get())
                {
                    case HEADERS ->
                    {
                        state.compareAndSet(State.HEADERS, State.BODY);
                        return new WriteRecord(false, ByteBuffer.wrap(GZIP_HEADER), Callback.NOOP);
                    }
                    case BODY ->
                    {
                        // Processing input
                        if (content.hasRemaining())
                        {
                            if (output == null)
                                output = compression.acquireByteBuffer(bufferSize);
                            if (encode(content, output.getByteBuffer()))
                            {
                                if (output.hasRemaining())
                                {
                                    WriteRecord writeRecord = new WriteRecord(false, output.getByteBuffer(), Callback.from(output::release));
                                    output = null;
                                    return writeRecord;
                                }
                            }
                        }
                        else if (inputBytesProvided > 0)
                        {
                            // no remaining content (and input has been provided)
                            return null;
                        }
                        if (!content.hasRemaining() && last)
                        {
                            state.compareAndSet(State.BODY, State.FLUSHING);
                            // Reset input, so that Gzip stops looking at ByteBuffer (that might be reused)
                            // deflater.setInput(EMPTY_BUFFER);
                            deflater.finish();
                        }
                    }
                    case FLUSHING ->
                    {
                        // flush anything left out of the deflater
                        if (output == null)
                            output = compression.acquireByteBuffer(bufferSize);
                        if (!flush(output.getByteBuffer()))
                            state.compareAndSet(State.FLUSHING, State.TRAILERS);
                        if (output.hasRemaining())
                        {
                            WriteRecord writeRecord = new WriteRecord(false, output.getByteBuffer(), Callback.from(output::release));
                            output = null;
                            return writeRecord;
                        }
                    }
                    case TRAILERS ->
                    {
                        if (output == null)
                            output = compression.acquireByteBuffer(16);
                        trailers(output.getByteBuffer());
                        state.compareAndSet(State.TRAILERS, State.FINISHED);
                        WriteRecord writeRecord = new WriteRecord(true, output.getByteBuffer(), Callback.from(output::release));
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
        inputBuffer.release();
        deflaterEntry.release();
    }

    /**
     * Encode the content, put output into output buffer.
     *
     * @param content the input (uncompressed) content.
     * @param output the output (compressed).
     * @return true if output was produced, false otherwise
     */
    private boolean encode(ByteBuffer content, ByteBuffer output)
    {
        if (content.hasRemaining())
            addInput(content);

        BufferUtil.clearToFill(output);
        int len = deflater.deflate(output);
        BufferUtil.flipToFlush(output, 0);
        return (len > 0);
    }

    /**
     * Flush the Gzip internals.
     *
     * @param output the output buffer to write to.
     * @return true if flush produced output, false to indicate no output produced.
     */
    private boolean flush(ByteBuffer output)
    {
        int pos = output.position();
        BufferUtil.flipToFill(output);
        while (!deflater.finished())
        {
            int len = deflater.deflate(output, Deflater.FULL_FLUSH);
            if (len > 0)
            {
                BufferUtil.flipToFlush(output, pos);
                return true;
            }
        }
        BufferUtil.flipToFlush(output, pos);
        return false;
    }

    private void trailers(ByteBuffer output)
    {
        // GZIP Trailers requires LITTLE_ENDIAN ByteBuffer.order
        assert output.order() == ByteOrder.LITTLE_ENDIAN;

        // need to write trailers
        output.clear();
        output.putInt((int)crc.getValue()); // CRC-32 of uncompressed data
        // Per javadoc, the .getBytesRead() is preferred as it is a return value of `long`.
        // The gzip trailer is fixed at a value of `int`, so we use the non-preferred .getTotalIn()
        // instead.  Also, if a gzip compressed is larger than Integer.MAX_VALUE then this trailer is broken anyway.
        output.putInt((int)deflater.getTotalIn()); // // Number of uncompressed bytes
        output.flip();
    }
}
