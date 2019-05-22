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

import org.junit.jupiter.api.Test;

import static org.eclipse.jetty.util.AtomicTriInteger.MAX_VALUE;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class AtomicTriIntegerTest
{

    @Test
    public void testBitOperations()
    {
        long encoded;
        
        encoded = AtomicTriInteger.encode(0,0,0);
        assertThat(AtomicTriInteger.getWord0(encoded),is(0));
        assertThat(AtomicTriInteger.getWord1(encoded),is(0));
        assertThat(AtomicTriInteger.getWord2(encoded),is(0));

        encoded = AtomicTriInteger.encode(1,2,3);
        assertThat(AtomicTriInteger.getWord0(encoded),is(1));
        assertThat(AtomicTriInteger.getWord1(encoded),is(2));
        assertThat(AtomicTriInteger.getWord2(encoded),is(3));

        encoded = AtomicTriInteger.encode(MAX_VALUE, MAX_VALUE, MAX_VALUE);
        assertThat(AtomicTriInteger.getWord0(encoded),is(MAX_VALUE));
        assertThat(AtomicTriInteger.getWord1(encoded),is(MAX_VALUE));
        assertThat(AtomicTriInteger.getWord2(encoded),is(MAX_VALUE));

        assertThrows(IllegalArgumentException.class,()-> AtomicTriInteger.encode(-1, MAX_VALUE, MAX_VALUE));
        assertThrows(IllegalArgumentException.class,()-> AtomicTriInteger.encode(MAX_VALUE, -1, MAX_VALUE));
        assertThrows(IllegalArgumentException.class,()-> AtomicTriInteger.encode(MAX_VALUE, MAX_VALUE, -1));
        assertThrows(IllegalArgumentException.class,()-> AtomicTriInteger.encode(MAX_VALUE+1, MAX_VALUE, MAX_VALUE));
        assertThrows(IllegalArgumentException.class,()-> AtomicTriInteger.encode(MAX_VALUE, MAX_VALUE+1, MAX_VALUE));
        assertThrows(IllegalArgumentException.class,()-> AtomicTriInteger.encode(MAX_VALUE, MAX_VALUE, MAX_VALUE+1));
    }

    @Test
    public void testSetGet()
    {
        AtomicTriInteger ati = new AtomicTriInteger();
        ati.set(1,2,3);
        assertThat(ati.getWord0(),is(1));
        assertThat(ati.getWord1(),is(2));
        assertThat(ati.getWord2(),is(3));
    }

    @Test
    public void testCompareAndSet()
    {
        AtomicTriInteger ati = new AtomicTriInteger();
        ati.set(1,2,3);
        long value = ati.get();

        ati.set(2,3,4);
        assertFalse(ati.compareAndSet(value,5,6,7));
        assertThat(ati.getWord0(),is(2));
        assertThat(ati.getWord1(),is(3));
        assertThat(ati.getWord2(),is(4));

        value = ati.get();
        assertTrue(ati.compareAndSet(value,6,7,8));
        assertThat(ati.getWord0(),is(6));
        assertThat(ati.getWord1(),is(7));
        assertThat(ati.getWord2(),is(8));
    }


    @Test
    public void testAdd()
    {
        AtomicTriInteger ati = new AtomicTriInteger();
        ati.set(1,2,3);
        ati.add(-1,-2,4);
        assertThat(ati.getWord0(),is(0));
        assertThat(ati.getWord1(),is(0));
        assertThat(ati.getWord2(),is(7));
    }
    
}
