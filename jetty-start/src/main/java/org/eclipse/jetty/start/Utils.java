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

        return join(arr, 0, arr.length, delim);
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
     * @param value the value to test
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
     * @param value the value to test
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
