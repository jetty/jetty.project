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

import java.util.concurrent.atomic.AtomicLong;

/**
 * An AtomicLong with additional methods to treat it as four 16 bit words.
 */
public class AtomicWords extends AtomicLong
{
    /**
     * Sets the hi and lo values.
     *
     * @param w0 the 0th word
     * @param w1 the 1st word
     * @param w2 the 2nd word
     * @param w3 the 3rd word
     */
    public void set(int w0, int w1, int w2, int w3)
    {
        set(encode(w0, w1, w2, w3));
    }

    /**
     * Atomically sets the word values to the given updated values only if
     * the current encoded value is as expected.
     *
     * @param expectEncoded the expected encoded value
     * @param w0 the 0th word
     * @param w1 the 1st word
     * @param w2 the 2nd word
     * @param w3 the 3rd word
     * @return {@code true} if successful.
     */
    public boolean compareAndSet(long expectEncoded, int w0, int w1, int w2, int w3)
    {
        return compareAndSet(expectEncoded,encode(w0, w1, w2, w3));
    }

    /**
     * Atomically adds the given deltas to the current hi and lo values.
     *
     * @param delta0 the delta to apply to the 0th word value
     * @param delta1 the delta to apply to the 1st word value
     * @param delta2 the delta to apply to the 2nd word value
     * @param delta3 the delta to apply to the 3rd word value
     */
    public void add(int delta0, int delta1, int delta2, int delta3)
    {
        while(true)
        {
            long encoded = get();
            long update = encode(
                getWord0(encoded)+delta0,
                getWord1(encoded)+delta1,
                getWord2(encoded)+delta2,
                getWord3(encoded)+delta3);
            if (compareAndSet(encoded,update))
                return;
        }
    }


    /**
     * Gets word 0 value
     *
     * @return the 16 bit value as an int
     */
    public int getWord0()
    {
        return getWord0(get());
    }


    /**
     * Gets word 1 value
     *
     * @return the 16 bit value as an int
     */
    public int getWord1()
    {
        return getWord1(get());
    }

    /**
     * Gets word 2 value
     *
     * @return the 16 bit value as an int
     */
    public int getWord2()
    {
        return getWord2(get());
    }

    /**
     * Gets word 3 value
     *
     * @return the 16 bit value as an int
     */
    public int getWord3()
    {
        return getWord3(get());
    }

    /**
     * Gets word 0 value from the given encoded value.
     *
     * @param encoded the encoded value
     * @return the 16 bit value as an int
     */
    public static int getWord0(long encoded)
    {
        return (int) ((encoded>>48)&0xFFFFL);
    }

    /**
     * Gets word 0 value from the given encoded value.
     *
     * @param encoded the encoded value
     * @return the 16 bit value as an int
     */
    public static int getWord1(long encoded)
    {
        return (int) ((encoded>>32)&0xFFFFL);
    }

    /**
     * Gets word 0 value from the given encoded value.
     *
     * @param encoded the encoded value
     * @return the 16 bit value as an int
     */
    public static int getWord2(long encoded)
    {
        return (int) ((encoded>>16)&0xFFFFL);
    }

    /**
     * Gets word 0 value from the given encoded value.
     *
     * @param encoded the encoded value
     * @return the 16 bit value as an int
     */
    public static int getWord3(long encoded)
    {
        return (int) (encoded&0xFFFFL);
    }

    /**
     * Encodes 4 16 bit words values into a long.
     *
     * @param w0 the 0th word
     * @param w1 the 1st word
     * @param w2 the 2nd word
     * @param w3 the 3rd word
     * @return the encoded value
     */
    public static long encode(int w0, int w1, int w2, int w3)
    {
        long wl0 = ((long)w0)&0xFFFFL;
        long wl1 = ((long)w1)&0xFFFFL;
        long wl2 = ((long)w2)&0xFFFFL;
        long wl3 = ((long)w3)&0xFFFFL;
        return (wl0<<48)+(wl1<<32)+(wl2<<16)+wl3;
    }
}
