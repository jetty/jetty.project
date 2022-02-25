//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.util;

import java.util.concurrent.atomic.AtomicLong;

/**
 * An AtomicLong with additional methods to treat it as two hi/lo integers.
 */
public class AtomicBiInteger extends AtomicLong
{

    public AtomicBiInteger()
    {
    }

    public AtomicBiInteger(long encoded)
    {
        super(encoded);
    }

    public AtomicBiInteger(int hi, int lo)
    {
        super(encode(hi, lo));
    }

    /**
     * @return the hi value
     */
    public int getHi()
    {
        return getHi(get());
    }

    /**
     * Gets a hi value from the given encoded value.
     *
     * @param encoded the encoded value
     * @return the hi value
     */
    public static int getHi(long encoded)
    {
        return (int)((encoded >> 32) & 0xFFFF_FFFFL);
    }

    /**
     * @return the lo value
     */
    public int getLo()
    {
        return getLo(get());
    }

    /**
     * Gets a lo value from the given encoded value.
     *
     * @param encoded the encoded value
     * @return the lo value
     */
    public static int getLo(long encoded)
    {
        return (int)(encoded & 0xFFFF_FFFFL);
    }

    /**
     * Atomically sets the hi value without changing the lo value.
     *
     * @param hi the new hi value
     * @return the previous hi value
     */
    public int getAndSetHi(int hi)
    {
        while (true)
        {
            long encoded = get();
            long update = encodeHi(encoded, hi);
            if (compareAndSet(encoded, update))
                return getHi(encoded);
        }
    }

    /**
     * Atomically sets the lo value without changing the hi value.
     *
     * @param lo the new lo value
     * @return the previous lo value
     */
    public int getAndSetLo(int lo)
    {
        while (true)
        {
            long encoded = get();
            long update = encodeLo(encoded, lo);
            if (compareAndSet(encoded, update))
                return getLo(encoded);
        }
    }

    /**
     * Sets the hi and lo values.
     *
     * @param hi the new hi value
     * @param lo the new lo value
     */
    public void set(int hi, int lo)
    {
        set(encode(hi, lo));
    }

    /**
     * <p>Atomically sets the hi value to the given updated value
     * only if the current value {@code ==} the expected value.</p>
     * <p>Concurrent changes to the lo value result in a retry.</p>
     *
     * @param expectHi the expected hi value
     * @param hi the new hi value
     * @return {@code true} if successful. False return indicates that
     * the actual hi value was not equal to the expected hi value.
     */
    public boolean compareAndSetHi(int expectHi, int hi)
    {
        while (true)
        {
            long encoded = get();
            if (getHi(encoded) != expectHi)
                return false;
            long update = encodeHi(encoded, hi);
            if (compareAndSet(encoded, update))
                return true;
        }
    }

    /**
     * <p>Atomically sets the lo value to the given updated value
     * only if the current value {@code ==} the expected value.</p>
     * <p>Concurrent changes to the hi value result in a retry.</p>
     *
     * @param expectLo the expected lo value
     * @param lo the new lo value
     * @return {@code true} if successful. False return indicates that
     * the actual lo value was not equal to the expected lo value.
     */
    public boolean compareAndSetLo(int expectLo, int lo)
    {
        while (true)
        {
            long encoded = get();
            if (getLo(encoded) != expectLo)
                return false;
            long update = encodeLo(encoded, lo);
            if (compareAndSet(encoded, update))
                return true;
        }
    }

    /**
     * Atomically sets the values to the given updated values only if
     * the current encoded value {@code ==} the expected encoded value.
     *
     * @param encoded the expected encoded value
     * @param hi the new hi value
     * @param lo the new lo value
     * @return {@code true} if successful. False return indicates that
     * the actual encoded value was not equal to the expected encoded value.
     */
    public boolean compareAndSet(long encoded, int hi, int lo)
    {
        long update = encode(hi, lo);
        return compareAndSet(encoded, update);
    }

    /**
     * Atomically sets the hi and lo values to the given updated values only if
     * the current hi and lo values {@code ==} the expected hi and lo values.
     *
     * @param expectHi the expected hi value
     * @param hi the new hi value
     * @param expectLo the expected lo value
     * @param lo the new lo value
     * @return {@code true} if successful. False return indicates that
     * the actual hi and lo values were not equal to the expected hi and lo value.
     */
    public boolean compareAndSet(int expectHi, int hi, int expectLo, int lo)
    {
        long encoded = encode(expectHi, expectLo);
        long update = encode(hi, lo);
        return compareAndSet(encoded, update);
    }

    /**
     * Atomically adds the given delta to the current hi value, returning the updated hi value.
     *
     * @param delta the delta to apply
     * @return the updated hi value
     */
    public int addAndGetHi(int delta)
    {
        while (true)
        {
            long encoded = get();
            int hi = getHi(encoded) + delta;
            long update = encodeHi(encoded, hi);
            if (compareAndSet(encoded, update))
                return hi;
        }
    }

    /**
     * Atomically adds the given delta to the current lo value, returning the updated lo value.
     *
     * @param delta the delta to apply
     * @return the updated lo value
     */
    public int addAndGetLo(int delta)
    {
        while (true)
        {
            long encoded = get();
            int lo = getLo(encoded) + delta;
            long update = encodeLo(encoded, lo);
            if (compareAndSet(encoded, update))
                return lo;
        }
    }

    /**
     * Atomically adds the given deltas to the current hi and lo values.
     *
     * @param deltaHi the delta to apply to the hi value
     * @param deltaLo the delta to apply to the lo value
     */
    public void add(int deltaHi, int deltaLo)
    {
        while (true)
        {
            long encoded = get();
            long update = encode(getHi(encoded) + deltaHi, getLo(encoded) + deltaLo);
            if (compareAndSet(encoded, update))
                return;
        }
    }

    /**
     * Encodes hi and lo values into a long.
     *
     * @param hi the hi value
     * @param lo the lo value
     * @return the encoded value
     */
    public static long encode(int hi, int lo)
    {
        long h = ((long)hi) & 0xFFFF_FFFFL;
        long l = ((long)lo) & 0xFFFF_FFFFL;
        return (h << 32) + l;
    }

    /**
     * Sets the hi value into the given encoded value.
     *
     * @param encoded the encoded value
     * @param hi the hi value
     * @return the new encoded value
     */
    public static long encodeHi(long encoded, int hi)
    {
        long h = ((long)hi) & 0xFFFF_FFFFL;
        long l = encoded & 0xFFFF_FFFFL;
        return (h << 32) + l;
    }

    /**
     * Sets the lo value into the given encoded value.
     *
     * @param encoded the encoded value
     * @param lo the lo value
     * @return the new encoded value
     */
    public static long encodeLo(long encoded, int lo)
    {
        long h = (encoded >> 32) & 0xFFFF_FFFFL;
        long l = ((long)lo) & 0xFFFF_FFFFL;
        return (h << 32) + l;
    }
}
