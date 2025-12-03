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

import org.eclipse.jetty.compression.DecoderConfig;
import org.eclipse.jetty.io.Content;

public class ZstandardDecoderConfig implements DecoderConfig
{
    private int bufferSize = org.eclipse.jetty.util.IO.DEFAULT_BUFFER_SIZE;
    private boolean magicless = false;

    @Override
    public int getBufferSize()
    {
        return bufferSize;
    }

    /**
     * Set Decoder input buffer size.
     *
     * <p>Make sure that the {@link ZstandardCompression#getByteBufferPool()} instance can pool
     * the specified value otherwise direct buffers have to be allocated then left to be
     * collected by the GC, which may have serious performance implications.</p>
     * @param size size of input buffer.
     */
    @Override
    public void setBufferSize(int size)
    {
        this.bufferSize = size;
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
     * {@link ZstandardCompression#newDecoderSource(Content.Source)} or
     * {@link ZstandardCompression#newDecoderSource(Content.Source, DecoderConfig)}.
     * </p>
     * <p>>
     * Note: not applied when using
     * {@link ZstandardCompression#newDecoderInputStream(InputStream)} or
     * {@link ZstandardCompression#newDecoderInputStream(InputStream, DecoderConfig)}.
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
