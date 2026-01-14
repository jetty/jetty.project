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
        assertEquals(1, ArrayUtil.growCapacity(0, 1, 1));
        assertEquals(2, ArrayUtil.growCapacity(1, 1, 2));
        assertThat(ArrayUtil.growCapacity(0, 1, Integer.MAX_VALUE), greaterThanOrEqualTo(1));
        assertThat(ArrayUtil.growCapacity(1, 1, Integer.MAX_VALUE), greaterThanOrEqualTo(2));
        assertThat(ArrayUtil.growCapacity(100, 2000, Integer.MAX_VALUE), greaterThanOrEqualTo(2100));
        assertEquals(Integer.MAX_VALUE, ArrayUtil.growCapacity(Integer.MAX_VALUE - 1, 1, Integer.MAX_VALUE));
        assertThrows(IllegalArgumentException.class, () -> ArrayUtil.growCapacity(Integer.MAX_VALUE, 1, Integer.MAX_VALUE));
    }
}
