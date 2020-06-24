//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.util.compression;

import java.util.zip.Deflater;

public class DeflaterPool extends CompressionPool<Deflater>
{
    private final int compressionLevel;
    private final boolean nowrap;

    /**
     * Create a Pool of {@link Deflater} instances.
     * <p>
     * If given a capacity equal to zero the Deflaters will not be pooled
     * and will be created on acquire and ended on release.
     * If given a negative capacity equal to zero there will be no size restrictions on the DeflaterPool
     *
     * @param capacity maximum number of Deflaters which can be contained in the pool
     * @param compressionLevel the default compression level for new Deflater objects
     * @param nowrap if true then use GZIP compatible compression for all new Deflater objects
     */
    public DeflaterPool(int capacity, int compressionLevel, boolean nowrap)
    {
        super(capacity);
        this.compressionLevel = compressionLevel;
        this.nowrap = nowrap;
    }

    @Override
    protected Deflater newObject()
    {
        return new Deflater(compressionLevel, nowrap);
    }

    @Override
    protected void end(Deflater deflater)
    {
        deflater.end();
    }

    @Override
    protected void reset(Deflater deflater)
    {
        deflater.reset();
    }
}
