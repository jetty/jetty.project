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

package org.eclipse.jetty.compression.zstandard;

import java.io.OutputStream;

import com.github.luben.zstd.Zstd;
import com.github.luben.zstd.ZstdOutputStreamNoFinalizer;
import org.eclipse.jetty.compression.EncoderConfig;
import org.eclipse.jetty.io.Content;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ZstandardEncoderConfig implements EncoderConfig
{
    /**
     * Default Buffer Size as found in zstd-jni.
     */
    public static final int DEFAULT_BUFFER_SIZE;
    public static final int MIN_BUFFER_SIZE = 32;
    private static final Logger LOG = LoggerFactory.getLogger(ZstandardDecoderConfig.class);

    static
    {
        // Get the recommended buffer size from zstd-jni (actually comes from zstandard lib),
        // but put some upper limit on it for our default buffer size.
        // The user can still configure the buffer size to be higher if they want to.
        long bufferSizeCeiling = 256_000;
        long bufferSize = ZstdOutputStreamNoFinalizer.recommendedCOutSize();
        if (bufferSize > bufferSizeCeiling)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Lowering zstd-jni recommended/default encoder buffer size of: {} to {}", bufferSize, bufferSizeCeiling);
            bufferSize = bufferSizeCeiling;
        }
        DEFAULT_BUFFER_SIZE = (int)bufferSize;
    }

    private int bufferSize = DEFAULT_BUFFER_SIZE;
    private int level = Zstd.defaultCompressionLevel();
    private int strategy = -1;
    private boolean magicless = false;
    private boolean checksum = false;

    @Override
    public int getBufferSize()
    {
        return bufferSize;
    }

    /**
     * Set Encoder output buffer size.
     *
     * <p>
     * Note: only applies when using
     * {@link ZstandardCompression#newEncoderSink(Content.Sink, EncoderConfig)} or
     * {@link ZstandardCompression#newEncoderSink(Content.Sink)}.
     * </p>
     * <p>>
     * Note: not applied when using
     * {@link ZstandardCompression#newEncoderOutputStream(OutputStream, EncoderConfig)} or
     * {@link ZstandardCompression#newEncoderOutputStream(OutputStream)}
     * </p>
     *
     * @param size size of output buffer.
     */
    @Override
    public void setBufferSize(int size)
    {
        this.bufferSize = Math.max(MIN_BUFFER_SIZE, size);
    }

    /**
     * Get Zstandard encoder compression level.
     *
     * @return the compression level.
     */
    @Override
    public int getCompressionLevel()
    {
        return level;
    }

    /**
     * Zstandard encoder compression level.
     *
     * @param level the level to set (in range 1 to 19)
     */
    @Override
    public void setCompressionLevel(int level)
    {
        // range of valid values
        if (level < 1 || level > 19)
        {
            throw new IllegalArgumentException("Compression Level should be in range [1, 19]");
        }
        this.level = level;
    }

    @Override
    public int getStrategy()
    {
        return strategy;
    }

    /**
     * Zstandard strategy.
     *
     * @param strategy the strategy to use (or -1 to use default zstd-jni strategy)
     * @see <a href="https://facebook.github.io/zstd/zstd_manual.html#Chapter5">Zstd Manual: Chapter 5</a>
     */
    @Override
    public void setStrategy(int strategy)
    {
        if ((strategy != -1) && ((strategy < 1) || (strategy > 9)))
            throw new IllegalArgumentException("Strategy should be in range [1, 9] (or -1 to use default behavior)");
        this.strategy = strategy;
    }

    public boolean isChecksum()
    {
        return checksum;
    }

    /**
     * Enable or disable compression checksums.
     *
     * @param flag true to enable, false is default.
     * @see com.github.luben.zstd.ZstdCompressCtx#setChecksum(boolean)
     */
    public void setChecksum(boolean flag)
    {
        this.checksum = flag;
    }

    public boolean isMagicless()
    {
        return magicless;
    }

    /**
     * Enable or disable magicless zstd frames.
     *
     * <p>
     * Note: only applies when using
     * {@link ZstandardCompression#newEncoderSink(Content.Sink, EncoderConfig)} or
     * {@link ZstandardCompression#newEncoderSink(Content.Sink)}.
     * </p>
     * <p>>
     * Note: not applied when using
     * {@link ZstandardCompression#newEncoderOutputStream(OutputStream, EncoderConfig)} or
     * {@link ZstandardCompression#newEncoderOutputStream(OutputStream)}
     * </p>
     *
     * @param flag true to enable, false is default.
     * @see com.github.luben.zstd.ZstdCompressCtx#setMagicless(boolean)
     */
    public void setMagicless(boolean flag)
    {
        this.magicless = flag;
    }
}
