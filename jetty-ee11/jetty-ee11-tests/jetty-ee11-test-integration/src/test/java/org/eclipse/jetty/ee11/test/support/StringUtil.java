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

package org.eclipse.jetty.ee11.test.support;

import java.util.List;
import java.util.ListIterator;

import org.eclipse.jetty.http.HttpTokens;

public class StringUtil
{
    public static boolean isNotBlank(String str)
    {
        return !org.eclipse.jetty.util.StringUtil.isBlank(str);
    }

    /**
     * Utility method to convert "\n" found to "\r\n" if running on windows.
     *
     * @param str input string.
     * @return the same string, with any LF or CR returned as system default.
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
                        ret.append(HttpTokens.LN);
                        linesep = false;
                    }
                    ret.append(c);
            }
        }

        return ret.toString();
    }

    public static void removeStartsWith(String prefix, List<String> lines)
    {
        ListIterator<String> it = lines.listIterator();
        while (it.hasNext())
        {
            String line = it.next();
            if (line.startsWith(prefix))
            {
                it.remove();
            }
        }
    }
}
