//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.start;

import java.util.Collection;

public final class Utils
{
    public static String join(Object[] arr, String delim)
    {
        if (arr == null)
        {
            return "";
        }

        return join(arr,0,arr.length,delim);
    }

    public static String join(Object[] arr, int start, int end, String delim)
    {
        if (arr == null)
        {
            return "";
        }
        StringBuilder str = new StringBuilder();
        for (int i = start; i < end; i++)
        {
            if (i > start)
            {
                str.append(delim);
            }
            str.append(arr[i]);
        }
        return str.toString();
    }

    public static String join(Collection<?> objs, String delim)
    {
        if (objs == null)
        {
            return "";
        }
        StringBuilder str = new StringBuilder();
        boolean needDelim = false;
        for (Object obj : objs)
        {
            if (needDelim)
            {
                str.append(delim);
            }
            str.append(obj);
            needDelim = true;
        }
        return str.toString();
    }

    /**
     * Is String null, empty, or consisting of only whitespace.
     * 
     * @param value
     *            the value to test
     * @return true if null, empty, or consisting of only whitespace
     */
    public static boolean isBlank(String value)
    {
        if (value == null)
        {
            return true;
        }
        int len = value.length();
        for (int i = 0; i < len; i++)
        {
            int c = value.codePointAt(i);
            if (!Character.isWhitespace(c))
            {
                return false;
            }
        }
        return true;
    }

    /**
     * Is String valid and has something other than whitespace
     * 
     * @param value
     *            the value to test
     * @return true if String has something other than whitespace
     */
    public static boolean isNotBlank(String value)
    {
        if (value == null)
        {
            return false;
        }
        int len = value.length();
        for (int i = 0; i < len; i++)
        {
            int c = value.codePointAt(i);
            if (!Character.isWhitespace(c))
            {
                return true;
            }
        }
        return false;
    }
}
