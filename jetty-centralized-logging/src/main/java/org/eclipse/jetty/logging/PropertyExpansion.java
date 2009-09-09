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
package org.eclipse.jetty.logging;

/**
 * Basic System Property string expansion "${user.home}"
 */
public class PropertyExpansion
{
    public static String expand(String s)
    {
        int i1 = 0;
        int i2 = 0;

        i1 = 0;
        i2 = 0;
        while (s != null)
        {
            i1 = s.indexOf("${",i2);
            if (i1 < 0)
                break;
            i2 = s.indexOf("}",i1 + 2);
            if (i2 < 0)
                break;
            String name = s.substring(i1 + 2,i2);
            String property = System.getProperty(name,"${" + name + "}");
            s = s.substring(0,i1) + property + s.substring(i2 + 1);
        }

        return s;
    }
}
