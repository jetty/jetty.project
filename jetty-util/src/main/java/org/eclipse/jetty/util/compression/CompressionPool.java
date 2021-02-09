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

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jetty.util.component.AbstractLifeCycle;

public abstract class CompressionPool<T> extends AbstractLifeCycle
{
    public static final int INFINITE_CAPACITY = -1;

    private final Queue<T> _pool;
    private final AtomicInteger _numObjects = new AtomicInteger(0);
    private final int _capacity;

    /**
     * Create a Pool of {@link T} instances.
     *
     * If given a capacity equal to zero the Objects will not be pooled
     * and will be created on acquire and ended on release.
     * If given a negative capacity equal to zero there will be no size restrictions on the Pool
     *
     * @param capacity maximum number of Objects which can be contained in the pool
     */
    public CompressionPool(int capacity)
    {
        _capacity = capacity;
        _pool = (_capacity == 0) ? null : new ConcurrentLinkedQueue<>();
    }

    protected abstract T newObject();

    protected abstract void end(T object);

    protected abstract void reset(T object);

    /**
     * @return Object taken from the pool if it is not empty or a newly created Object
     */
    public T acquire()
    {
        T object;

        if (_capacity == 0)
            object = newObject();
        else
        {
            object = _pool.poll();
            if (object == null)
                object = newObject();
            else if (_capacity > 0)
                _numObjects.decrementAndGet();
        }

        return object;
    }

    /**
     * @param object returns this Object to the pool or calls {@link #end(Object)} if the pool is full.
     */
    public void release(T object)
    {
        if (object == null)
            return;

        if (_capacity == 0 || !isRunning())
        {
            end(object);
            return;
        }
        else if (_capacity < 0)
        {
            reset(object);
            _pool.add(object);
        }
        else
        {
            while (true)
            {
                int d = _numObjects.get();

                if (d >= _capacity)
                {
                    end(object);
                    break;
                }

                if (_numObjects.compareAndSet(d, d + 1))
                {
                    reset(object);
                    _pool.add(object);
                    break;
                }
            }
        }
    }

    @Override
    public void doStop()
    {
        T t = _pool.poll();
        while (t != null)
        {
            end(t);
            t = _pool.poll();
        }
        _numObjects.set(0);
    }

    @Override
    public String toString()
    {
        StringBuilder str = new StringBuilder();
        str.append(getClass().getSimpleName());
        str.append('@').append(Integer.toHexString(hashCode()));
        str.append('{').append(getState());
        str.append(",size=").append(_pool == null ? -1 : _pool.size());
        str.append(",capacity=").append(_capacity <= 0 ? "UNLIMITED" : _capacity);
        str.append('}');
        return str.toString();
    }
}
