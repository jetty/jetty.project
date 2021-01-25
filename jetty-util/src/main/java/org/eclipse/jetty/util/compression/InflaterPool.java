//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.util.compression;

import java.util.zip.Inflater;

public class InflaterPool extends CompressionPool<Inflater>
{
    private final boolean nowrap;

    /**
     * Create a Pool of {@link Inflater} instances.
     * <p>
     * If given a capacity equal to zero the Inflaters will not be pooled
     * and will be created on acquire and ended on release.
     * If given a negative capacity equal to zero there will be no size restrictions on the InflaterPool
     *
     * @param capacity maximum number of Inflaters which can be contained in the pool
     * @param nowrap if true then use GZIP compatible compression for all new Inflater objects
     */
    public InflaterPool(int capacity, boolean nowrap)
    {
        super(capacity);
        this.nowrap = nowrap;
    }

    @Override
    protected Inflater newObject()
    {
        return new Inflater(nowrap);
    }

    @Override
    protected void end(Inflater inflater)
    {
        inflater.end();
    }

    @Override
    protected void reset(Inflater inflater)
    {
        inflater.reset();
    }
}
