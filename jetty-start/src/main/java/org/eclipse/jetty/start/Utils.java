//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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

public final class Utils
{
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
