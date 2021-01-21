//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

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
}
