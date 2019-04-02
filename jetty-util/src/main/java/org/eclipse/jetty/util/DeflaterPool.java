//
//  ========================================================================
//  Copyright (c) 1995-2019 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.util;

import org.apache.commons.pool.ObjectPool;

import java.util.zip.Deflater;

public class DeflaterPool
{
    private final ObjectPool<Deflater> deflaterPool;

    /**
     * Create a Pool of {@link Deflater} instances.
     * <p>
     * If given a capacity equal to zero the Deflaters will not be pooled
     * and will be created on acquire and ended on release.
     * If given a negative capacity equal to zero there will be no size restrictions on the DeflaterPool
     *
     * @param capacity         maximum number of Deflaters which can be contained in the pool
     * @param compressionLevel the default compression level for new Deflater objects
     * @param nowrap           if true then use GZIP compatible compression for all new Deflater objects
     */
    public DeflaterPool(int capacity, int compressionLevel, boolean nowrap)
    {
        deflaterPool = capacity <= 0
                ? CompressionPool.growingDeflaterPool(compressionLevel, nowrap)
                : CompressionPool.limitedDeflaterPool(capacity, compressionLevel, nowrap);
    }

    /**
     * @return Deflater taken from the pool if it is not empty or a newly created Deflater
     */
    public Deflater acquire()
    {
        try
        {
            return deflaterPool.borrowObject();
        }
        catch (Exception e)
        {
            throw new RuntimeException(e);
        }
    }

    /**
     * @param deflater returns this Deflater to the pool or calls deflater.end() if the pool is full.
     */
    public void release(Deflater deflater)
    {
        if (deflater == null)
            return;

        try
        {
            deflaterPool.returnObject(deflater);
        }
        catch (Exception e)
        {
            throw new RuntimeException(e);
        }
    }
}
