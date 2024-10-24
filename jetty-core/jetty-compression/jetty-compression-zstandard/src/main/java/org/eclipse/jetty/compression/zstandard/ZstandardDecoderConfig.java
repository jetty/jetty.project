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

import java.io.InputStream;

import com.github.luben.zstd.ZstdInputStreamNoFinalizer;
import org.eclipse.jetty.compression.DecoderConfig;
import org.eclipse.jetty.io.Content;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ZstandardDecoderConfig implements DecoderConfig
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
        long bufferSize = ZstdInputStreamNoFinalizer.recommendedDOutSize();
        if (bufferSize > bufferSizeCeiling)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Lowering zstd-jni recommended/default decoder buffer size of: {} to {}", bufferSize, bufferSizeCeiling);
            bufferSize = bufferSizeCeiling;
        }
        DEFAULT_BUFFER_SIZE = (int)bufferSize;
    }

    private int bufferSize = DEFAULT_BUFFER_SIZE;
    private boolean magicless = false;

    @Override
    public int getBufferSize()
    {
        return bufferSize;
    }

    @Override
    public void setBufferSize(int size)
    {
        this.bufferSize = this.bufferSize = Math.max(MIN_BUFFER_SIZE, size);
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
     * {@link ZstandardCompression#newDecoderSource(Content.Source, DecoderConfig)} or
     * {@link ZstandardCompression#newDecoderSource(Content.Source, DecoderConfig)}.
     * </p>
     * <p>>
     * Note: not applied when using
     * {@link ZstandardCompression#newDecoderInputStream(InputStream, DecoderConfig)} or
     * {@link ZstandardCompression#newDecoderInputStream(InputStream)}
     * </p>
     *
     * @param flag true to enable, false is default.
     * @see com.github.luben.zstd.ZstdDecompressCtx#setMagicless(boolean)
     */
    public void setMagicless(boolean flag)
    {
        this.magicless = flag;
    }
}
