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
import java.util.zip.CRC32;
import java.util.zip.Deflater;

import org.eclipse.jetty.compression.EncoderSink;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.compression.CompressionPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GzipEncoderSink extends EncoderSink
{
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
    private final CRC32 crc = new CRC32();
    private final int flushMode;
    private boolean headersWritten = false;

    public GzipEncoderSink(GzipCompression compression, Content.Sink sink)
    {
        super(sink);
        this.compression = compression;
        this.deflaterEntry = compression.getDeflaterPool().acquire();
        this.deflaterEntry.get().setLevel(compression.getCompressionLevel());
        this.flushMode = Deflater.NO_FLUSH;
        this.crc.reset();
        // TODO: need place for deflaterEntry.release()
    }

    private void addTrailer(Deflater deflater, ByteBuffer outputBuffer)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("addTrailer: crc={}, bytesRead={}, bytesWritten={}", crc.getValue(), deflater.getBytesRead(), deflater.getBytesWritten());
        outputBuffer.putInt((int)crc.getValue()); // CRC-32 of uncompressed data
        outputBuffer.putInt((int)deflater.getBytesRead()); // // Number of uncompressed bytes
    }

    @Override
    public void write(boolean last, ByteBuffer byteBuffer, Callback callback)
    {
        if (!headersWritten)
        {
            // Add GZIP Header
            offerWrite(false, ByteBuffer.wrap(GZIP_HEADER), Callback.NOOP);
            headersWritten = true;
        }

        boolean callbackHandled = false;
        RetainableByteBuffer outputBuffer = null;
        Deflater deflater = deflaterEntry.get();
        while (byteBuffer.hasRemaining())
        {
            if (deflater.needsInput())
            {
                crc.update(byteBuffer.slice());
                deflater.setInput(byteBuffer);
            }

            if (outputBuffer == null)
                outputBuffer = compression.acquireByteBuffer();

            outputBuffer.getByteBuffer().clear();
            deflater.deflate(outputBuffer.getByteBuffer(), flushMode);
            outputBuffer.getByteBuffer().flip();
            if (outputBuffer.hasRemaining())
            {
                Callback writeCallback = Callback.from(outputBuffer::release);
                if (!last && !byteBuffer.hasRemaining())
                {
                    callbackHandled = true;
                    writeCallback = Callback.combine(callback, writeCallback);
                }
                offerWrite(false, outputBuffer.getByteBuffer(), writeCallback);
                outputBuffer = null;
            }
        }
        // Reset input, so that Gzip stops looking at ByteBuffer (that might be reused)
        deflater.setInput(EMPTY_BUFFER);

        if (last)
        {
            // declare input finished
            deflater.finish();
            while (!deflater.finished())
            {
                if (outputBuffer == null)
                    outputBuffer = compression.acquireByteBuffer();

                outputBuffer.getByteBuffer().clear();
                int len = deflater.deflate(outputBuffer.getByteBuffer(), Deflater.FULL_FLUSH);
                if (len > 0)
                {
                    outputBuffer.getByteBuffer().flip();
                    Callback writeCallback = Callback.from(outputBuffer::release);
                    offerWrite(false, outputBuffer.getByteBuffer(), writeCallback);
                    outputBuffer = null;
                }
            }

            // need to write trailers
            if (outputBuffer == null)
                outputBuffer = compression.acquireByteBuffer();
            callbackHandled = true;
            outputBuffer.getByteBuffer().clear();
            addTrailer(deflater, outputBuffer.getByteBuffer());
            outputBuffer.getByteBuffer().flip();
            Callback writeCallback = Callback.combine(callback, Callback.from(outputBuffer::release));
            offerWrite(true, outputBuffer.getByteBuffer(), writeCallback);
            outputBuffer = null;
        }

        if (outputBuffer != null)
            outputBuffer.release();
        if (!callbackHandled)
            callback.succeeded();
    }
}
