// ========================================================================
// Copyright (c) Webtide LLC
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
//
// The Eclipse Public License is available at 
// http://www.eclipse.org/legal/epl-v10.html
//
// The Apache License v2.0 is available at
// http://www.apache.org/licenses/LICENSE-2.0.txt
//
// You may elect to redistribute this code under either of these licenses. 
// ========================================================================

package org.eclipse.jetty.test.support;

public class StringUtil
{
    public static final String LN = System.getProperty("line.separator");
    
    public static boolean isBlank(String str)
    {
        if (str == null)
        {
            return true;
        }

        int len = str.length();
        if (len == 0)
        {
            return true;
        }

        char c;
        for (int i = 0; i < str.length(); i++)
        {
            c = str.charAt(i);
            if (Character.isWhitespace(c) == false)
            {
                return false;
            }
        }

        return true;
    }

    public static boolean isNotBlank(String str)
    {
        return !StringUtil.isBlank(str);
    }

    public static String[] split(String s, char delim)
    {
        if (s == null)
        {
            return null;
        }

        if (s.length() <= 0)
        {
            return new String[] {}; // empty array
        }

        String ret[];

        int count = 0;
        int offset = 0;
        int idx;

        // Calculate entry length to not waste memory.
        while ((idx = s.indexOf(delim,offset)) != (-1))
        {
            if (idx > offset)
            {
                count++;
            }
            offset = idx + 1;
        }
        if (s.length() > offset)
        {
            count++;
        }

        // Create return array.
        offset = 0;
        ret = new String[count];
        int retIdx = 0;
        while ((idx = s.indexOf(delim,offset)) != (-1))
        {
            if (idx > offset)
            {
                ret[retIdx] = s.substring(offset,idx);
                retIdx++;
            }
            offset = idx + 1;
        }
        if (s.length() > offset)
        {
            ret[retIdx] = s.substring(offset);
        }

        return ret;
    }
    
    /**
     * Utility method to convert "\n" found to "\r\n" if running on windows.
     * 
     * @param str
     *            input string.
     * @return
     */
    public static String toSystemLN(String str)
    {
        boolean linesep = false;
        StringBuffer ret = new StringBuffer();
        for (char c : str.toCharArray())
        {
            switch (c)
            {
                case '\r':
                    linesep = true;
                    break;
                case '\n':
                    linesep = true;
                    break;
                default:
                    if (linesep)
                    {
                        ret.append(LN);
                        linesep = false;
                    }
                    ret.append(c);
            }
        }

        return ret.toString();
    }
}
