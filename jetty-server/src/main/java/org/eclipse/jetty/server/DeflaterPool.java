//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.server;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.Deflater;

public class DeflaterPool
{
    private final Queue<Deflater> _pool;
    private final int _compressionLevel;
    private final boolean _nowrap;
    private final AtomicInteger _numDeflaters = new AtomicInteger(0);
    private final int _capacity;


    /**
     * Create a Pool of {@link Deflater} instances.
     *
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
        _capacity = capacity;
        _compressionLevel = compressionLevel;
        _nowrap = nowrap;

        if (_capacity != 0)
            _pool = new ConcurrentLinkedQueue<>();
        else
            _pool = null;
    }

    protected Deflater newDeflater()
    {
        return new Deflater(_compressionLevel, _nowrap);
    }

    /**
     * @return Deflater taken from the pool if it is not empty or a newly created Deflater
     */
    public Deflater acquire()
    {
        Deflater deflater;

        if (_capacity == 0)
            deflater = newDeflater();
        else if (_capacity < 0)
        {
            deflater = _pool.poll();
            if (deflater == null)
                deflater = newDeflater();
        }
        else
        {
            deflater = _pool.poll();
            if (deflater == null)
                deflater = newDeflater();
            else
                _numDeflaters.decrementAndGet();
        }

        return deflater;
    }

    /**
     * @param deflater returns this Deflater to the pool or calls deflater.end() if the pool is full.
     */
    public void release(Deflater deflater)
    {
        if (deflater == null)
            return;

        if (_capacity == 0)
        {
            deflater.end();
            return;
        }
        else if (_capacity < 0)
        {
            deflater.reset();
            _pool.add(deflater);
        }
        else
        {
            while (true)
            {
                int d = _numDeflaters.get();

                if (d >= _capacity)
                {
                    deflater.end();
                    break;
                }

                if (_numDeflaters.compareAndSet(d, d + 1))
                {
                    deflater.reset();
                    _pool.add(deflater);
                    break;
                }
            }
        }
    }
}
