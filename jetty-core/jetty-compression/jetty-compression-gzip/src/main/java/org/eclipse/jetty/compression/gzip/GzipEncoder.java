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

import org.eclipse.jetty.compression.Compression;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.compression.CompressionPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GzipEncoder implements Compression.Encoder
{
    private static final Logger LOG = LoggerFactory.getLogger(GzipEncoder.class);

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

    private final CRC32 crc = new CRC32();
    private final boolean syncFlush;
    private CompressionPool<Deflater>.Entry deflaterEntry;
    private boolean headersOutputted = false;

    public GzipEncoder(GzipCompression gzipCompression)
    {
        deflaterEntry = gzipCompression.getDeflaterPool().acquire();
        syncFlush = gzipCompression.isSyncFlush();
        crc.reset();
    }

    @Override
    public void addTrailer(ByteBuffer outputBuffer)
    {
        Deflater deflater = deflaterEntry.get();
        if (LOG.isDebugEnabled())
            LOG.debug("addTrailer: crc={}, bytesRead={}, bytesWritten={}", crc.getValue(), deflater.getBytesRead(), deflater.getBytesWritten());
        outputBuffer.putInt((int)crc.getValue()); // CRC-32 of uncompressed data
        outputBuffer.putInt((int)deflater.getBytesRead()); // // Number of uncompressed bytes
    }

    @Override
    public int encode(ByteBuffer outputBuffer)
    {
        int size = 0;
        if (!headersOutputted)
        {
            // Add GZIP Header
            outputBuffer.put(GZIP_HEADER, 0, GZIP_HEADER.length);
            size += GZIP_HEADER.length;
            headersOutputted = true;
        }

        Deflater deflater = deflaterEntry.get();
        size += deflater.deflate(outputBuffer, getFlushMode());
        return size;
    }

    @Override
    public void finishInput()
    {
        deflaterEntry.get().finish();
    }

    @Override
    public int getTrailerSize()
    {
        return GZIP_TRAILER_SIZE;
    }

    @Override
    public boolean isOutputFinished()
    {
        return deflaterEntry.get().finished();
    }

    @Override
    public boolean needsInput()
    {
        return deflaterEntry.get().needsInput();
    }

    @Override
    public void close()
    {
        if (deflaterEntry != null)
        {
            deflaterEntry.release();
            deflaterEntry = null;
        }
    }

    @Override
    public void addInput(ByteBuffer content)
    {
        Deflater deflater = deflaterEntry.get();
        if (LOG.isDebugEnabled())
            LOG.debug("setInput({})", BufferUtil.toDetailString(content));
        crc.update(content.slice());
        deflater.setInput(content);
    }

    private int getFlushMode()
    {
        return syncFlush ? Deflater.SYNC_FLUSH : Deflater.NO_FLUSH;
    }
}
