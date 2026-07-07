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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

import org.eclipse.jetty.compression.EncoderSink;
import org.eclipse.jetty.compression.gzip.GzipCompression;
import org.eclipse.jetty.compression.gzip.GzipEncoderConfig;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.util.buffer.ReadableBuffer;
import org.eclipse.jetty.util.buffer.WritableBuffer;
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
    private final ReadableBuffer inputBuffer;
    private final int bufferSize;
    private final int flushMode;
    private final CRC32 crc = new CRC32();
    private final AtomicReference<State> state = new AtomicReference<>(State.HEADERS);
    private boolean released;

    public GzipEncoderSink(GzipCompression compression, Content.Sink sink, GzipEncoderConfig config)
    {
        super(sink);
        this.compression = compression;
        this.deflaterEntry = compression.getDeflaterPool().acquire();
        this.deflater = deflaterEntry.get();
        this.bufferSize = config.getBufferSize();
        this.inputBuffer = compression.acquireBuffer(bufferSize).toReadable();
        this.deflater.reset();
        try
        {
            inputBuffer.writeTo(this.deflater::setInput);
        }
        catch (IOException e)
        {
            inputBuffer.release();
            throw new UncheckedIOException(e);
        }
        this.deflater.setStrategy(config.getStrategy());
        this.deflater.setLevel(config.getCompressionLevel());
        this.flushMode = config.isSyncFlush() ? Deflater.SYNC_FLUSH : Deflater.NO_FLUSH;
        this.crc.reset();
    }

    protected void addInput(ReadableBuffer content)
    {
        WritableBuffer wb = inputBuffer.toWritable();
        try
        {
            long space = Math.min(wb.remaining(), content.remaining());
            ReadableBuffer slice = content.slice(content.position(), space);
            // Update CRC based on what can be consumed right now.
            // Any leftover content will be consumed on a later call.
            slice.writeTo(buffer ->
            {
                int position = buffer.position();
                crc.update(buffer);
                buffer.position(position);
            });
            wb.put(slice);
            slice.release();
            // consume the bytes on content
            content.position(content.position() + space);
        }
        catch (IOException e)
        {
            throw new UncheckedIOException(e);
        }
        finally
        {
            wb.toReadable();
        }
    }

    @Override
    protected WriteRecord encode(boolean last, ReadableBuffer content)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("encode() state={}, last={}, content={}", state, last, content);

        if (released)
            throw new IllegalStateException("Already released");

        WritableBuffer output = null;
        try
        {
            while (true)
            {
                switch (state.get())
                {
                    case HEADERS ->
                    {
                        state.compareAndSet(State.HEADERS, State.BODY);
                        return new WriteRecord(false, ReadableBuffer.wrap(GZIP_HEADER));
                    }
                    case BODY ->
                    {
                        // Processing input
                        if (content != null && content.remaining() > 0L)
                        {
                            if (output == null)
                                output = compression.acquireBuffer(bufferSize);
                            if (encode(content, output))
                            {
                                WriteRecord writeRecord = new WriteRecord(false, output.toReadable());
                                output = null;
                                return writeRecord;
                            }
                        }
                        else
                        {
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
                        if (output.position() > 0L)
                        {
                            WriteRecord writeRecord = new WriteRecord(false, output.toReadable());
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
                        WriteRecord writeRecord = new WriteRecord(true, output.toReadable());
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
        inputBuffer.release();
        deflaterEntry.release();
    }

    /**
     * Encode the content, put output into output buffer.
     *
     * @param content the input (uncompressed) content.
     * @param outputBuffer the output (compressed).
     * @return true if output was produced, false otherwise
     */
    private boolean encode(ReadableBuffer content, WritableBuffer outputBuffer)
    {
        if (content != null && content.remaining() > 0L)
            addInput(content);

        try
        {
            long len = outputBuffer.readFrom(output ->
            {
                deflater.deflate(output, flushMode);
                return false;
            });
            return (len > 0L);
        }
        catch (IOException e)
        {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Flush the Gzip internals.
     *
     * @param outputBuffer the output buffer to write to.
     * @return true if flush produced output, false to indicate no output produced.
     */
    private boolean flush(WritableBuffer outputBuffer)
    {
        while (!deflater.finished())
        {
            try
            {
                long len = outputBuffer.readFrom(output ->
                {
                    deflater.deflate(output, flushMode);
                    return false;
                });
                if (len > 0L)
                    return true;
            }
            catch (IOException e)
            {
                throw new UncheckedIOException(e);
            }
        }
        return false;
    }

    private void trailers(WritableBuffer outputBuffer)
    {
        // GZIP Trailers requires LITTLE_ENDIAN ByteBuffer.order
        // TODO restore endianness check?
//        assert output.order() == ByteOrder.LITTLE_ENDIAN;

        // need to write trailers
        outputBuffer.putInt((int)crc.getValue()); // CRC-32 of uncompressed data
        // Per javadoc, the .getBytesRead() is preferred as it is a return value of `long`.
        // The gzip trailer is fixed at a value of `int`, so we use the non-preferred .getTotalIn()
        // instead.  Also, if a gzip compressed is larger than Integer.MAX_VALUE then this trailer is broken anyway.
        outputBuffer.putInt(deflater.getTotalIn()); // // Number of uncompressed bytes
    }
}
