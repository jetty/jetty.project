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
import java.util.zip.CRC32;
import java.util.zip.Deflater;

import org.eclipse.jetty.compression.Compression;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.compression.CompressionPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GzipEncoder implements Compression.Encoder
{
    private static final Logger LOG = LoggerFactory.getLogger(GzipEncoder.class);

    // Per RFC-1952 this is the "unknown" OS value byte.
    private static final byte OS_UNKNOWN = (byte)0xFF;
    private static final byte[] GZIP_HEADER = new byte[]{
        (byte)0x1f, (byte)0x8b, Deflater.DEFLATED, 0, 0, 0, 0, 0, 0, OS_UNKNOWN
    };

    // Per RFC-1952, the GZIP trailer is 8 bytes
    private static final int GZIP_TRAILER_SIZE = 8;

    private final CRC32 crc = new CRC32();
    private final ByteBufferPool byteBufferPool;
    private final int outputBufferSize;
    private final boolean syncFlush;
    private CompressionPool<Deflater>.Entry deflaterEntry;

    public GzipEncoder(GzipCompression gzipCompression, ByteBufferPool bufferPool, int outputBufferSize)
    {
        this.byteBufferPool = bufferPool;
        this.outputBufferSize = Math.max(GZIP_HEADER.length + GZIP_TRAILER_SIZE, outputBufferSize);
        this.deflaterEntry = gzipCompression.getDeflaterPool().acquire();
        this.syncFlush = gzipCompression.isSyncFlush();
    }

    @Override
    public void begin()
    {
        crc.reset();
    }

    @Override
    public void cleanup()
    {
        if (deflaterEntry != null)
        {
            deflaterEntry.release();
            deflaterEntry = null;
        }
    }

    @Override
    public void setInput(ByteBuffer content)
    {
        crc.update(content.slice());
        Deflater deflater = deflaterEntry.get();
        deflater.setInput(content);
    }

    @Override
    public void finish()
    {
        deflaterEntry.get().finish();
    }

    @Override
    public int trailerSize()
    {
        return GZIP_TRAILER_SIZE;
    }

    @Override
    public RetainableByteBuffer initialBuffer()
    {
        RetainableByteBuffer buffer = byteBufferPool.acquire(outputBufferSize, false);
        ByteBuffer byteBuffer = buffer.getByteBuffer();
        // Per RFC-1952, GZIP is LITTLE_ENDIAN
        byteBuffer.order(ByteOrder.LITTLE_ENDIAN);
        BufferUtil.flipToFill(byteBuffer);
        // Add GZIP Header
        byteBuffer.put(GZIP_HEADER, 0, GZIP_HEADER.length);
        return buffer;
    }

    private int getFlushMode()
    {
        return syncFlush ? Deflater.SYNC_FLUSH : Deflater.NO_FLUSH;
    }

    @Override
    public void addTrailer(ByteBuffer outputBuffer)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("addTrailer: _crc={}, _totalIn={})", crc.getValue(), deflaterEntry.get().getTotalIn());
        outputBuffer.putInt((int)crc.getValue());
        outputBuffer.putInt(deflaterEntry.get().getTotalIn());
    }

    @Override
    public boolean finished()
    {
        return deflaterEntry.get().finished();
    }

    @Override
    public boolean needsInput()
    {
        return deflaterEntry.get().needsInput();
    }

    @Override
    public int encode(ByteBuffer outputBuffer)
    {
        Deflater deflater = deflaterEntry.get();
        return deflater.deflate(outputBuffer, getFlushMode());
    }
}
