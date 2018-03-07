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

package org.eclipse.jetty.util;

import java.util.concurrent.atomic.AtomicLong;

/**
 * An AtomicLong with additional methods to treat it has 
 * two hi/lo integers.
 *
 */
public class AtomicBiInteger extends AtomicLong
{    
    public int getHi()
    {
        return getHi(get());
    }
    
    public int getLo()
    {
        return getLo(get());
    }

    public int setHi(int hi)
    {
        while(true)
        {
            long encoded = get();
            long update = encodeHi(encoded,hi);
            if (compareAndSet(encoded,update))
                return getHi(encoded);
        }
    }
    
    public int setLo(int lo)
    {
        while(true)
        {
            long encoded = get();
            long update = encodeLo(encoded,lo);
            if (compareAndSet(encoded,update))
                return getLo(encoded);
        }
    }
    
    public void set(int hi, int lo)
    {
        set(encode(hi,lo));
    }

    public boolean compareAndSetHi(int expect, int hi)
    {
        while(true)
        {
            long encoded = get();
            if (getHi(encoded)!=expect)
                return false;
            long update = encodeHi(encoded,hi);
            if (compareAndSet(encoded,update))
                return true;
        }
    }
    
    public boolean compareAndSetLo(int expect, int lo)
    {
        while(true)
        {
            long encoded = get();
            if (getLo(encoded)!=expect)
                return false;
            long update = encodeLo(encoded,lo);
            if (compareAndSet(encoded,update))
                return true;
        }
    }
    
    public boolean compareAndSet(long expect, int hi, int lo)
    {
        long encoded = get();
        long update = encode(hi,lo);
        return compareAndSet(encoded,update);
    }

    public boolean compareAndSet(int expectHi, int hi, int expectLo, int lo)
    {
        long encoded = encode(expectHi,expectLo);
        long update = encode(hi,lo);
        return compareAndSet(encoded,update);
    }
        
    public int updateHi(int delta)
    {
        while(true)
        {
            long encoded = get();
            int hi = getHi(encoded)+delta;
            long update = encodeHi(encoded,hi);
            if (compareAndSet(encoded,update))
                return hi;
        }
    }  
    
    public int updateLo(int delta)
    {
        while(true)
        {
            long encoded = get();
            int lo = getLo(encoded)+delta;
            long update = encodeLo(encoded,lo);
            if (compareAndSet(encoded,update))
                return lo;
        }
    }
    
    public void update(int deltaHi, int deltaLo)
    {
        while(true)
        {
            long encoded = get();
            long update = encode(getHi(encoded)+deltaHi, getLo(encoded)+deltaLo);
            if (compareAndSet(encoded,update))
                return;
        }
    }

    public boolean compareAndSetHiUpdateLo(int expect, int hi, int deltaLo)
    {
        while(true)
        {
            long encoded = get();
            if (getHi(encoded)!=expect)
                return false;
            long update = encode(hi,getLo(encoded)+deltaLo);
            if (compareAndSet(encoded,update))
                return true;
        }
    }
    
    public boolean compareAndSetLoUpdateHi(int expect, int lo, int deltaHi)
    {
        while(true)
        {
            long encoded = get();
            if (getLo(encoded)!=expect)
                return false;
            long update = encode(getHi(encoded)+deltaHi, lo);
            if (compareAndSet(encoded,update))
                return true;
        }
    }
    
    
    
    public static int getHi(long encoded)
    {
        return (int) ((encoded>>32)&0xFFFF_FFFFl);
    }
    
    public static int getLo(long encoded)
    {
        return (int) (encoded&0xFFFF_FFFFl);
    }
    
    public static long encode(int hi, int lo)
    {
        long h = ((long)hi)&0xFFFF_FFFFl;
        long l = ((long)lo)&0xFFFF_FFFFl;
        long encoded = (h<<32)+l;
        return encoded;
    }
    
    public static long encodeLo(long encoded, int lo)
    {
        long h = (encoded>>32)&0xFFFF_FFFFl;
        long l = ((long)lo)&0xFFFF_FFFFl;
        encoded = (h<<32)+l;
        return encoded;
    }
    
    public static long encodeHi(long encoded, int hi)
    {
        long h = ((long)hi)&0xFFFF_FFFFl;
        long l = encoded&0xFFFF_FFFFl;
        encoded = (h<<32)+l;
        return encoded;
    }
    
    
    
    
    
}
