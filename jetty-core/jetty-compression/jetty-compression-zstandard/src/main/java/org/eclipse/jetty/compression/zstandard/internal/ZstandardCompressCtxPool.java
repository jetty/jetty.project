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

package org.eclipse.jetty.compression.zstandard.internal;

import com.github.luben.zstd.ZstdCompressCtx;
import org.eclipse.jetty.util.compression.CompressionPool;

/**
 * <p>A pool of {@link ZstdCompressCtx} instances.</p>
 * <p>A {@link ZstdCompressCtx} allocates its native workspace on its first compression,
 * and keeps it across {@link ZstdCompressCtx#reset() resets}; pooling the contexts avoids
 * allocating and freeing that workspace (several MiB at the default level) for each response.</p>
 */
public class ZstandardCompressCtxPool extends CompressionPool<ZstdCompressCtx>
{
    public static final int DEFAULT_CAPACITY = 32;

    /**
     * <p>Create a Pool of {@link ZstdCompressCtx} instances.</p>
     * <p>If given a capacity equal to zero the contexts will not be pooled
     * and will be created on acquire and closed on release.</p>
     *
     * @param capacity maximum number of contexts which can be contained in the pool
     */
    public ZstandardCompressCtxPool(int capacity)
    {
        super(capacity);
    }

    @Override
    protected ZstdCompressCtx newPooled()
    {
        return new ZstdCompressCtx();
    }

    @Override
    protected void end(ZstdCompressCtx compressCtx)
    {
        compressCtx.close();
    }

    @Override
    protected void reset(ZstdCompressCtx compressCtx)
    {
        // Resets both the session and the parameters,
        // but retains the native workspace.
        compressCtx.reset();
    }
}
