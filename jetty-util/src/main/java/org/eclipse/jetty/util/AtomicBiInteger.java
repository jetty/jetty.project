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
 */
public class AtomicBiInteger extends AtomicLong
{    
    /**
     * @return the hi integer value
     */
    public int getHi()
    {
        return getHi(get());
    }
    
    /**
     * @return the lo integer value
     */
    public int getLo()
    {
        return getLo(get());
    }

    /**
     * Atomically set the hi integer value without changing
     * the lo value. 
     * @param hi the new hi value
     * @return the hi int value
     */
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

    /**
     * Atomically set the lo integer value without changing
     * the hi value. 
     * @param lo the new lo value
     */
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
    
    /**
     * Set the hi and lo integer values.
     * @param hi the new hi value
     * @param lo the new lo value
     */
    public void set(int hi, int lo)
    {
        set(encode(hi,lo));
    }

    /**
     * Atomically sets the hi int value to the given updated value
     * only if the current value {@code ==} the expected value.
     * Concurrent changes to the lo value result in a retry.
     * @param expect the expected value
     * @param hi the new value
     * @return {@code true} if successful. False return indicates that
     * the actual value was not equal to the expected value.
     */
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

    /**
     * Atomically sets the lo int value to the given updated value
     * only if the current value {@code ==} the expected value.
     * Concurrent changes to the hi value result in a retry.
     * @param expect the expected value
     * @param lo the new value
     * @return {@code true} if successful. False return indicates that
     * the actual value was not equal to the expected value.
     */
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

    /**
     * Atomically sets the values to the given updated values
     * only if the current encoded value {@code ==} the expected value.
     * @param expect the expected encoded values
     * @param hi the new hi value
     * @param lo the new lo value
     * @return {@code true} if successful. False return indicates that
     * the actual value was not equal to the expected value.
     */
    public boolean compareAndSet(long expect, int hi, int lo)
    {
        long encoded = get();
        long update = encode(hi,lo);
        return compareAndSet(encoded,update);
    }

    /**
     * Atomically sets the values to the given updated values
     * only if the current encoded value {@code ==} the expected value.
     * @param expectHi the expected hi values
     * @param hi the new hi value
     * @param expectLo the expected lo values
     * @param lo the new lo value
     * @return {@code true} if successful. False return indicates that
     * the actual value was not equal to the expected value.
     */
    public boolean compareAndSet(int expectHi, int hi, int expectLo, int lo)
    {
        long encoded = encode(expectHi,expectLo);
        long update = encode(hi,lo);
        return compareAndSet(encoded,update);
    }

    /**
     * Atomically updates the current hi value with the results of
     * applying the given delta, returning the updated value. 
     *
     * @param delta the delta to apply
     * @return the updated value
     */
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

    /**
     * Atomically updates the current lo value with the results of
     * applying the given delta, returning the updated value. 
     *
     * @param delta the delta to apply
     * @return the updated value
     */
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

    /**
     * Atomically updates the current values with the results of
     * applying the given deltas. 
     *
     * @param deltaHi the delta to apply to the hi value
     * @param deltaLo the delta to apply to the lo value
     */
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
    
    /**
     * Get a hi int value from an encoded long
     * @param encoded the encoded value
     * @return the hi int value
     */
    public static int getHi(long encoded)
    {
        return (int) ((encoded>>32)&0xFFFF_FFFFl);
    }

    /**
     * Get a lo int value from an encoded long
     * @param encoded the encoded value
     * @return the lo int value
     */
    public static int getLo(long encoded)
    {
        return (int) (encoded&0xFFFF_FFFFl);
    }
    
    /**
     * Encode hi and lo int values into a long
     * @param hi the hi int value
     * @param lo the lo int value
     * @return the encoded value
     *  
     */
    public static long encode(int hi, int lo)
    {
        long h = ((long)hi)&0xFFFF_FFFFl;
        long l = ((long)lo)&0xFFFF_FFFFl;
        long encoded = (h<<32)+l;
        return encoded;
    }


    /**
     * Encode hi int values into an already encoded long
     * @param encoded the encoded value
     * @param hi the hi int value
     * @return the encoded value
     *  
     */
    public static long encodeHi(long encoded, int hi)
    {
        long h = ((long)hi)&0xFFFF_FFFFl;
        long l = encoded&0xFFFF_FFFFl;
        encoded = (h<<32)+l;
        return encoded;
    }
    
    /**
     * Encode lo int values into an already encoded long
     * @param encoded the encoded value
     * @param lo the lo int value
     * @return the encoded value
     *  
     */
    public static long encodeLo(long encoded, int lo)
    {
        long h = (encoded>>32)&0xFFFF_FFFFl;
        long l = ((long)lo)&0xFFFF_FFFFl;
        encoded = (h<<32)+l;
        return encoded;
    }
    
    
    
    
    
}
