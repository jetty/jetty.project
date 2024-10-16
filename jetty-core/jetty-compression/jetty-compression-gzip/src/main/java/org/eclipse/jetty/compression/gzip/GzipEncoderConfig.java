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

import java.util.zip.Deflater;

import org.eclipse.jetty.compression.EncoderConfig;

public class GzipEncoderConfig implements EncoderConfig
{
    /**
     * Default Buffer Size as found in {@link java.util.zip.GZIPOutputStream}.
     */
    public static final int DEFAULT_BUFFER_SIZE = 512;
    /**
     * Minimum buffer size to avoid issues with JDK-8133170
     */
    public static final int MIN_BUFFER_SIZE = 8;

    private int bufferSize = DEFAULT_BUFFER_SIZE;
    private int level = Deflater.DEFAULT_COMPRESSION;
    private int strategy = Deflater.DEFAULT_STRATEGY;
    private boolean syncFlush = false;

    @Override
    public int getBufferSize()
    {
        return this.bufferSize;
    }

    @Override
    public void setBufferSize(int size)
    {
        this.bufferSize = Math.max(MIN_BUFFER_SIZE, size);
    }

    @Override
    public int getCompressionLevel()
    {
        return this.level;
    }

    @Override
    public void setCompressionLevel(int level)
    {
        if ((level != Deflater.DEFAULT_COMPRESSION) && ((level < 0 || level > 9)))
        {
            throw new IllegalArgumentException(
                "Compression Level should be in range [0, 9] (or " + Deflater.class.getName() + ".DEFAULT_COMPRESSION to use default level)");
        }
        this.level = level;
    }

    @Override
    public int getStrategy()
    {
        return this.strategy;
    }

    @Override
    public void setStrategy(int strategy)
    {
        if (strategy != Deflater.DEFAULT_STRATEGY ||
            strategy != Deflater.FILTERED ||
            strategy != Deflater.HUFFMAN_ONLY)
            throw new IllegalArgumentException("Unrecognized strategy: " + strategy);
        this.strategy = strategy;
    }

    /**
     * Is the {@link Deflater} running {@link Deflater#SYNC_FLUSH} or not.
     *
     * @return True if {@link Deflater#SYNC_FLUSH} is used, else {@link Deflater#NO_FLUSH}
     * @see #setSyncFlush(boolean)
     */
    public boolean isSyncFlush()
    {
        return syncFlush;
    }

    /**
     * Set the {@link Deflater} flush mode to use.  {@link Deflater#SYNC_FLUSH}
     * should be used if the application wishes to stream the data, but this may
     * hurt compression performance.
     *
     * @param syncFlush True if {@link Deflater#SYNC_FLUSH} is used, else {@link Deflater#NO_FLUSH}
     * @see #isSyncFlush()
     */
    public void setSyncFlush(boolean syncFlush)
    {
        this.syncFlush = syncFlush;
    }
}
