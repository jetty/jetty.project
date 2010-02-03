// ========================================================================
// Copyright (c) 2009-2009 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
// The Eclipse Public License is available at 
// http://www.eclipse.org/legal/epl-v10.html
// The Apache License v2.0 is available at
// http://www.opensource.org/licenses/apache2.0.php
// You may elect to redistribute this code under either of these licenses. 
// ========================================================================

package org.eclipse.jetty.util;

import static java.lang.Math.abs;

import java.util.concurrent.atomic.AtomicLong;


/* ------------------------------------------------------------ */
/**
 */
public class SimpleStats 
{
    private final AtomicLong _curr;
    private final AtomicLong _total;
    private final AtomicLong _min;
    private final AtomicLong _max;
    
    /* ------------------------------------------------------------ */
    /**
     */
    public SimpleStats()
    {
        this(true, true, true, true);
    }

    /* ------------------------------------------------------------ */
    /**
     * @param curr
     * @param total
     * @param min
     * @param max
     */
    public SimpleStats(boolean curr, boolean total, boolean min, boolean max)
    {
        _curr = new AtomicLong(curr ? 0 : -1);
        _total = new AtomicLong(total ? 0 : -1);
        _min = new AtomicLong(min ? 0 : -1);
        _max = new AtomicLong(max ? 0 : -1);
    }
    
    public void reset()
    {
        if (_curr.get() != -1)
            _curr.set(0);
        
        if (_total.get() != -1)
            _total.set(0);
        
        if (_min.get() != -1)
            _min.set(0);
        
        if (_max.get() != -1)
            _max.set(0);   
    }
    
    /* ------------------------------------------------------------ */
    /**
     * @param value
     */
    public void set(final long value)
    {
        if (_curr.get() != -1)
            _curr.set(value);
        
        if (_total.get() != -1)
            _total.addAndGet(value);
        
        if (_min.get() != -1)
            updateMin(value);
        
        if (_max.get() != -1)
            updateMax(value);
    }
    
    /* ------------------------------------------------------------ */
    /**
     * @param delta
     */
    public void add(final long delta)
    {
        if (_curr.get() != -1)
        {
            long curr = _curr.addAndGet(delta);
            
            if (_min.get() != -1)
                updateMin(curr);
            
            if (_max.get() != -1)
                updateMax(curr);
        }
        
        if (_total.get() != -1 && delta < 0)
            _total.addAndGet(abs(delta));
    }
    
    /* ------------------------------------------------------------ */
    /**
     */
    public void increment()
    {
        add(1);
    }
    
    /* ------------------------------------------------------------ */
    /**
     */
    public void decrement()
    {
        add(-1);
    }
    
    /* ------------------------------------------------------------ */
    /**
     * @return
     */
    public long getCurrent()
    {
        return _curr.get();
    }
    
    /* ------------------------------------------------------------ */
    /**
     * @return
     */
    public long getTotal()
    {
        return _total.get();
    }
    
    /* ------------------------------------------------------------ */
    /**
     * @return
     */
    public long getMax()
    {
        return _max.get();
    }
    
    /* ------------------------------------------------------------ */
    /**
     * @return
     */
    public long getMin()
    {
        return _min.get();
    }
  
     /* ------------------------------------------------------------ */
    /**
     * @param value
     */
    private void updateMin(long value)
    {
        long oldValue = _min.get();
        while (value < oldValue)
        {
            if (_min.compareAndSet(oldValue, value))
                break;
            oldValue = _min.get();
        }
    }

    /* ------------------------------------------------------------ */
    /**
     * @param value
     */
    private void updateMax(long value)
    {
        long oldValue = _max.get();
        while (value > oldValue)
        {
            if (_max.compareAndSet(oldValue, value))
                break;
            oldValue = _max.get();
        }
    }
}
