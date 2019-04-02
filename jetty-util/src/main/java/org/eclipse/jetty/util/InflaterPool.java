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

import java.util.zip.Inflater;

public class InflaterPool
{

    private final ObjectPool<Inflater> inflaterPool;

    /**
     * Create a Pool of {@link Inflater} instances.
     * <p>
     * If given a capacity equal to zero the Inflaters will not be pooled
     * and will be created on acquire and ended on release.
     * If given a negative capacity equal to zero there will be no size restrictions on the InflaterPool
     *
     * @param capacity maximum number of Inflaters which can be contained in the pool
     * @param nowrap   if true then use GZIP compatible compression for all new Inflater objects
     */
    public InflaterPool(int capacity, boolean nowrap)
    {
        inflaterPool = (capacity <= 0)
                ? CompressionPool.growingInflaterPool(nowrap)
                : CompressionPool.limitedInflaterPool(capacity, nowrap);
    }

    /**
     * @return Inflater taken from the pool if it is not empty or a newly created Inflater
     */
    public Inflater acquire()
    {
        try
        {
            return inflaterPool.borrowObject();
        }
        catch (Exception e)
        {
            throw new RuntimeException(e);
        }
    }

    /**
     * @param inflater returns this Inflater to the pool or calls inflater.end() if the pool is full.
     */
    public void release(Inflater inflater)
    {
        try
        {
            inflaterPool.returnObject(inflater);
        }
        catch (Exception e)
        {
            throw new RuntimeException(e);
        }
    }
}
