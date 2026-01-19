//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
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
import static org.hamcrest.Matchers.*;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Unit tests for class {@link ArrayUtil}.
 *
 * @see ArrayUtil
 */
public class ArrayUtilTest
{

    @Test
    public void testAddToArrayWithEmptyArray()
    {
        String[] stringArray = new String[0];
        String[] resultArray = ArrayUtil.addToArray(stringArray, "Ca?", Object.class);

        assertEquals(0, stringArray.length);
        assertEquals(1, resultArray.length);

        assertNotSame(stringArray, resultArray);
        assertNotSame(resultArray, stringArray);

        assertFalse(resultArray.equals(stringArray));
        assertEquals(String.class, resultArray[0].getClass());
    }

    @Test
    public void testAddUsingNull()
    {
        String[] stringArray = new String[7];
        String[] stringArrayTwo = ArrayUtil.add(stringArray, null);

        assertEquals(7, stringArray.length);
        assertEquals(7, stringArrayTwo.length);

        assertSame(stringArray, stringArrayTwo);
        assertSame(stringArrayTwo, stringArray);
    }

    @Test
    public void testAddWithNonEmptyArray()
    {
        Object[] objectArray = new Object[3];
        Object[] objectArrayTwo = ArrayUtil.add(objectArray, objectArray);

        assertEquals(3, objectArray.length);
        assertEquals(6, objectArrayTwo.length);

        assertNotSame(objectArray, objectArrayTwo);
        assertNotSame(objectArrayTwo, objectArray);

        assertFalse(objectArrayTwo.equals(objectArray));
    }

    @Test
    public void testRemoveFromNullArrayReturningNull()
    {
        assertNull(ArrayUtil.removeFromArray((Integer[])null, new Object()));
    }

    @Test
    public void testRemoveNulls()
    {
        Object[] objectArray = new Object[2];
        objectArray[0] = new Object();
        Object[] resultArray = ArrayUtil.removeNulls(objectArray);

        assertEquals(2, objectArray.length);
        assertEquals(1, resultArray.length);

        assertNotSame(objectArray, resultArray);
        assertNotSame(resultArray, objectArray);

        assertFalse(resultArray.equals(objectArray));
    }

    @Test
    public void testGrowCapacity()
    {
        assertThrows(IllegalArgumentException.class, () -> ArrayUtil.growCapacity(-1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> ArrayUtil.growCapacity(0, -1, 1));
        assertThrows(IllegalArgumentException.class, () -> ArrayUtil.growCapacity(0, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> ArrayUtil.growCapacity(0, 1, -1));
        assertThrows(IllegalArgumentException.class, () -> ArrayUtil.growCapacity(0, 1, 0));
        assertThrows(IllegalArgumentException.class, () -> ArrayUtil.growCapacity(1, 1, 1));
        assertThat(ArrayUtil.growCapacity(0, 1, 1), is(1));
        assertThat(ArrayUtil.growCapacity(1, 1, 2), is(2));
        assertThat(ArrayUtil.growCapacity(0, 1, Integer.MAX_VALUE), greaterThanOrEqualTo(1));
        assertThat(ArrayUtil.growCapacity(1, 1, Integer.MAX_VALUE), greaterThanOrEqualTo(2));
        assertThat(ArrayUtil.growCapacity(100, 2000, Integer.MAX_VALUE), greaterThanOrEqualTo(2100));
        assertThat(ArrayUtil.growCapacity(1000, 1, 1100), allOf(greaterThanOrEqualTo(1001), lessThanOrEqualTo(1100)));
        assertThat(ArrayUtil.growCapacity(Integer.MAX_VALUE - 1, 1, Integer.MAX_VALUE), is(Integer.MAX_VALUE));
        assertThrows(IllegalArgumentException.class, () -> ArrayUtil.growCapacity(Integer.MAX_VALUE, 1, Integer.MAX_VALUE));
    }

    @Test
    public void testGrowByteArray()
    {
        byte[] bytes = new byte[]{0, 1, 2, 3};
        assertThrows(IllegalArgumentException.class, () -> ArrayUtil.grow(bytes, -1, 1));
        assertThrows(IllegalArgumentException.class, () -> ArrayUtil.grow(bytes, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> ArrayUtil.grow(bytes, 1, -1));
        assertThrows(IllegalArgumentException.class, () -> ArrayUtil.grow(bytes, 1, 0));
        assertThrows(IllegalArgumentException.class, () -> ArrayUtil.grow(bytes, 1, 1));

        byte[] grown = ArrayUtil.grow(bytes, 2, Integer.MAX_VALUE);
        assertThat(grown.length, greaterThanOrEqualTo(bytes.length + 2));
        for (int i = 0; i < grown.length; i++)
        {
            if (i < bytes.length)
                assertThat(grown[i], is((byte)i));
            else
                assertThat(grown[i], is((byte)0));
        }
    }

    @Test
    public void testGrowObjectArray()
    {
        String[] strings = new String[]{"0", "1", "2", "3"};
        assertThrows(IllegalArgumentException.class, () -> ArrayUtil.grow(strings, -1, 1));
        assertThrows(IllegalArgumentException.class, () -> ArrayUtil.grow(strings, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> ArrayUtil.grow(strings, 1, -1));
        assertThrows(IllegalArgumentException.class, () -> ArrayUtil.grow(strings, 1, 0));
        assertThrows(IllegalArgumentException.class, () -> ArrayUtil.grow(strings, 1, 1));

        String[] grown = ArrayUtil.grow(strings, 2, Integer.MAX_VALUE);
        assertThat(grown.length, greaterThanOrEqualTo(strings.length + 2));
        for (int i = 0; i < grown.length; i++)
        {
            if (i < strings.length)
                assertThat(grown[i], is(String.valueOf(i)));
            else
                assertThat(grown[i], nullValue());
        }
    }
}
