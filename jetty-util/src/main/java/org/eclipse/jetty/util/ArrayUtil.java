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

import java.io.Serializable;
import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Utility methods for Array manipulation
 */
public class ArrayUtil
    implements Cloneable, Serializable
{

    public static <T> T[] removeFromArray(T[] array, Object item)
    {
        if (item == null || array == null)
            return array;
        for (int i = array.length; i-- > 0; )
        {
            if (item.equals(array[i]))
            {
                Class<?> c = array == null ? item.getClass() : array.getClass().getComponentType();
                @SuppressWarnings("unchecked")
                T[] na = (T[])Array.newInstance(c, Array.getLength(array) - 1);
                if (i > 0)
                    System.arraycopy(array, 0, na, 0, i);
                if (i + 1 < array.length)
                    System.arraycopy(array, i + 1, na, i, array.length - (i + 1));
                return na;
            }
        }
        return array;
    }

    /**
     * Add arrays
     *
     * @param array1 An array to add to (or null)
     * @param array2 An array to add to (or null)
     * @param <T> the array entry type
     * @return new array with contents of both arrays, or null if both arrays are null
     */
    public static <T> T[] add(T[] array1, T[] array2)
    {
        if (array1 == null || array1.length == 0)
            return array2;
        if (array2 == null || array2.length == 0)
            return array1;

        T[] na = Arrays.copyOf(array1, array1.length + array2.length);
        System.arraycopy(array2, 0, na, array1.length, array2.length);
        return na;
    }

    /**
     * Add element to an array
     *
     * @param array The array to add to (or null)
     * @param item The item to add
     * @param type The type of the array (in case of null array)
     * @param <T> the array entry type
     * @return new array with contents of array plus item
     */
    public static <T> T[] addToArray(T[] array, T item, Class<?> type)
    {
        if (array == null)
        {
            if (type == null && item != null)
                type = item.getClass();
            @SuppressWarnings("unchecked")
            T[] na = (T[])Array.newInstance(type, 1);
            na[0] = item;
            return na;
        }
        else
        {
            T[] na = Arrays.copyOf(array, array.length + 1);
            na[array.length] = item;
            return na;
        }
    }

    /**
     * Add element to the start of an array
     *
     * @param array The array to add to (or null)
     * @param item The item to add
     * @param type The type of the array (in case of null array)
     * @param <T> the array entry type
     * @return new array with contents of array plus item
     */
    public static <T> T[] prependToArray(T item, T[] array, Class<?> type)
    {
        if (array == null)
        {
            if (type == null && item != null)
                type = item.getClass();
            @SuppressWarnings("unchecked")
            T[] na = (T[])Array.newInstance(type, 1);
            na[0] = item;
            return na;
        }
        else
        {
            Class<?> c = array.getClass().getComponentType();
            @SuppressWarnings("unchecked")
            T[] na = (T[])Array.newInstance(c, Array.getLength(array) + 1);
            System.arraycopy(array, 0, na, 1, array.length);
            na[0] = item;
            return na;
        }
    }

    /**
     * @param array Any array of object
     * @param <E> the array entry type
     * @return A new <i>modifiable</i> list initialised with the elements from <code>array</code>.
     */
    public static <E> List<E> asMutableList(E[] array)
    {
        if (array == null || array.length == 0)
            return new ArrayList<E>();
        return new ArrayList<E>(Arrays.asList(array));
    }

    public static <T> T[] removeNulls(T[] array)
    {
        for (T t : array)
        {
            if (t == null)
            {
                List<T> list = new ArrayList<>();
                for (T t2 : array)
                {
                    if (t2 != null)
                        list.add(t2);
                }
                return list.toArray(Arrays.copyOf(array, list.size()));
            }
        }
        return array;
    }
}

