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

package org.eclipse.jetty.util.statistic;

import java.util.concurrent.TimeUnit;

public class OnOffStatistic
{
    boolean _on;
    long _last;
    long _activations;
    long _lastOn;
    long _totalOn;
    long _totalOff;
    long _maxOn;
    
    public OnOffStatistic()
    {   
        this(false);
    }

    public OnOffStatistic(boolean on)
    {
        _on = on;
        _activations = on?1:0;
        _last = System.nanoTime();
    }
    
    public boolean record(boolean on)
    {
        synchronized (this)
        {
            if (on==_on)
                return false;
            
            long now = System.nanoTime();
            long period = now - _last;
            if (on)
            {
                _activations++;
                _totalOff += period;
            }
            else
            {
                _totalOn += period;
                if (period>_maxOn)
                    _maxOn = period;
                _lastOn = period;
            }

            _on = on;
            _last = now;
        }
        return true;
    }

    public String toString()
    {
        return toString(TimeUnit.MILLISECONDS);
    }

    public String toString(TimeUnit units)
    {
        synchronized (this)
        {
            long now = System.nanoTime();
            long period = now - _last;
            long maxOn = _maxOn;
            long totalOn = _totalOn;
            long totalOff = _totalOff;
            long lastOn = _lastOn;
            if (_on)
            {
                lastOn = period;
                totalOn+=period;
                if (period>maxOn)
                    maxOn=period;
            }
            else
            {
                totalOff+=period;
            }
            
            long totalTime = totalOn+totalOff;
            return String.format("{(%s) on=%d/%d(%d%%) last/ave/max on=%d/%d/%d activations=%d}",
                    units,
                    units.convert(totalOn,TimeUnit.NANOSECONDS),
                    units.convert(totalTime,TimeUnit.NANOSECONDS),
                    totalOn*100/totalTime,
                    units.convert(lastOn,TimeUnit.NANOSECONDS),
                    units.convert(totalOn/_activations,TimeUnit.NANOSECONDS),
                    units.convert(maxOn,TimeUnit.NANOSECONDS),
                    _activations);
        }
    }

    public long getLastOn(TimeUnit units)
    {
        synchronized (this)
        {
            if (_on)
                return units.convert(System.nanoTime()-_last,TimeUnit.NANOSECONDS);
            return units.convert(_lastOn,TimeUnit.NANOSECONDS);
        }
    }
    
    public long getMaxOn(TimeUnit units)
    {
        synchronized (this)
        {
            long l = _on?System.nanoTime()-_last:_lastOn;
            return units.convert(Math.max(l,_maxOn),TimeUnit.NANOSECONDS);
        }
    }
    
    
    public static void main(String... arg) throws Exception
    {
        OnOffStatistic oos = new OnOffStatistic();
        System.err.println(oos);
        Thread.sleep(100);
        System.err.println(oos);
        oos.record(true);
        System.err.println(oos);
        Thread.sleep(100);
        System.err.println(oos);
        Thread.sleep(100);
        System.err.println(oos);
        oos.record(false);
        System.err.println(oos);
        Thread.sleep(100);
        System.err.println(oos);
        
    }
    
}
