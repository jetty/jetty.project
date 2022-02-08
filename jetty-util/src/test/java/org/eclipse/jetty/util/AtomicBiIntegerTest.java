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

import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class AtomicBiIntegerTest
{

    @Test
    public void testBitOperations()
    {
        long encoded;

        encoded = AtomicBiInteger.encode(0, 0);
        assertThat(AtomicBiInteger.getHi(encoded), is(0));
        assertThat(AtomicBiInteger.getLo(encoded), is(0));

        encoded = AtomicBiInteger.encode(1, 2);
        assertThat(AtomicBiInteger.getHi(encoded), is(1));
        assertThat(AtomicBiInteger.getLo(encoded), is(2));

        encoded = AtomicBiInteger.encode(Integer.MAX_VALUE, -1);
        assertThat(AtomicBiInteger.getHi(encoded), is(Integer.MAX_VALUE));
        assertThat(AtomicBiInteger.getLo(encoded), is(-1));
        encoded = AtomicBiInteger.encodeLo(encoded, 42);
        assertThat(AtomicBiInteger.getHi(encoded), is(Integer.MAX_VALUE));
        assertThat(AtomicBiInteger.getLo(encoded), is(42));

        encoded = AtomicBiInteger.encode(-1, Integer.MAX_VALUE);
        assertThat(AtomicBiInteger.getHi(encoded), is(-1));
        assertThat(AtomicBiInteger.getLo(encoded), is(Integer.MAX_VALUE));
        encoded = AtomicBiInteger.encodeHi(encoded, 42);
        assertThat(AtomicBiInteger.getHi(encoded), is(42));
        assertThat(AtomicBiInteger.getLo(encoded), is(Integer.MAX_VALUE));

        encoded = AtomicBiInteger.encode(Integer.MIN_VALUE, 1);
        assertThat(AtomicBiInteger.getHi(encoded), is(Integer.MIN_VALUE));
        assertThat(AtomicBiInteger.getLo(encoded), is(1));
        encoded = AtomicBiInteger.encodeLo(encoded, Integer.MAX_VALUE);
        assertThat(AtomicBiInteger.getHi(encoded), is(Integer.MIN_VALUE));
        assertThat(AtomicBiInteger.getLo(encoded), is(Integer.MAX_VALUE));

        encoded = AtomicBiInteger.encode(1, Integer.MIN_VALUE);
        assertThat(AtomicBiInteger.getHi(encoded), is(1));
        assertThat(AtomicBiInteger.getLo(encoded), is(Integer.MIN_VALUE));
        encoded = AtomicBiInteger.encodeHi(encoded, Integer.MAX_VALUE);
        assertThat(AtomicBiInteger.getHi(encoded), is(Integer.MAX_VALUE));
        assertThat(AtomicBiInteger.getLo(encoded), is(Integer.MIN_VALUE));
    }

    @Test
    public void testSet()
    {
        AtomicBiInteger abi = new AtomicBiInteger();
        assertThat(abi.getHi(), is(0));
        assertThat(abi.getLo(), is(0));

        abi.getAndSetHi(Integer.MAX_VALUE);
        assertThat(abi.getHi(), is(Integer.MAX_VALUE));
        assertThat(abi.getLo(), is(0));

        abi.getAndSetLo(Integer.MIN_VALUE);
        assertThat(abi.getHi(), is(Integer.MAX_VALUE));
        assertThat(abi.getLo(), is(Integer.MIN_VALUE));
    }

    @Test
    public void testCompareAndSet()
    {
        AtomicBiInteger abi = new AtomicBiInteger();
        assertThat(abi.getHi(), is(0));
        assertThat(abi.getLo(), is(0));

        assertFalse(abi.compareAndSetHi(1, 42));
        assertTrue(abi.compareAndSetHi(0, 42));
        assertThat(abi.getHi(), is(42));
        assertThat(abi.getLo(), is(0));

        assertFalse(abi.compareAndSetLo(1, -42));
        assertTrue(abi.compareAndSetLo(0, -42));
        assertThat(abi.getHi(), is(42));
        assertThat(abi.getLo(), is(-42));
    }
}
